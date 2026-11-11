package com.english9x.org.members;

import com.english9x.audit.api.AuditLog;
import com.english9x.identity.api.EmailNotVerifiedException;
import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.Role;
import com.english9x.org.internal.InvitationService;
import com.english9x.org.members.MemberDtos.BulkInviteResponse;
import com.english9x.org.members.MemberDtos.CreateJoinCodeRequest;
import com.english9x.org.members.MemberDtos.ImportResult;
import com.english9x.org.members.MemberDtos.JoinCodeCreated;
import com.english9x.org.members.MemberDtos.JoinCodeView;
import com.english9x.org.members.MemberDtos.JoinResponse;
import com.english9x.org.members.MemberProblems.RowProblem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Members of an organisation. Bulk invitations and the import go through {@code InvitationService} (so each is audited {@code invitation.send} and
 * rate-limited per inviter). A join code is generated here, shown to its creator once, and only its SHA-256 digest is stored. Redeeming and removing
 * go through the definer functions; the audit record is written in the same transaction.
 */
@Service
class MemberService {

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I
    static final int CODE_LENGTH = 8;
    static final int MAX_IMPORT_ROWS = 1000;
    static final long MAX_WORKBOOK_BYTES = 2L * 1024 * 1024;
    static final Duration MAX_CODE_TTL = Duration.ofDays(366);
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MemberRepository repo;
    private final Authz authz;
    private final InvitationService invitations;
    private final AuditLog audit;
    private final Clock clock;

    MemberService(MemberRepository repo, Authz authz, InvitationService invitations, AuditLog audit, Clock clock) {
        this.repo = repo;
        this.authz = authz;
        this.invitations = invitations;
        this.audit = audit;
        this.clock = clock;
    }

    /** Same rule as the other /api/orgs/{id} endpoints: that organisation's ORG_ADMIN in its context, or a SYS_ADMIN outside any. */
    private void manageable(UUID orgId) {
        Optional<UUID> ctx = CurrentContextHolder.current().flatMap(CurrentContextHolder.Binding::orgId);
        if (ctx.isPresent()) {
            if (!ctx.get().equals(orgId)) {
                throw MemberProblems.notFound();
            }
            authz.require(Role.ORG_ADMIN, Optional.empty());
        } else {
            authz.require(Role.SYS_ADMIN, Optional.empty());
        }
        if (!repo.orgExists(orgId)) {
            throw MemberProblems.notFound();
        }
    }

    private static UUID actingUser() {
        return CurrentContextHolder.current().orElseThrow(() -> new AccessDeniedException("no user")).userId();
    }

    // ---- invitations -------------------------------------------------------------------------------------------------------

    @Transactional
    BulkInviteResponse invite(UUID orgId, List<String> emails) {
        manageable(orgId);
        Set<String> unique = new LinkedHashSet<>();
        for (String e : emails) {
            unique.add(e.strip().toLowerCase(Locale.ROOT));
        }
        for (String e : unique) {
            invitations.inviteLearner(orgId, e, null);
        }
        return new BulkInviteResponse(unique.size());
    }

    private record ImportRow(int row, String email, UUID classId) {}

    /** All rows or none: every row is checked first, any error rejects the file with the rows listed; otherwise every row is invited in this transaction. */
    @Transactional
    ImportResult importWorkbook(UUID orgId, MultipartFile file) {
        manageable(orgId);
        if (file == null || file.isEmpty() || file.getSize() > MAX_WORKBOOK_BYTES) {
            throw MemberProblems.notWorkbook();
        }
        List<RowProblem> errors = new ArrayList<>();
        List<ImportRow> rows = new ArrayList<>();
        Map<String, UUID> classes = repo.classesByName(orgId);
        try (InputStream in = file.getInputStream()) {
            byte[] magic = in.readNBytes(2);
            if (magic.length < 2 || magic[0] != 'P' || magic[1] != 'K') {
                throw MemberProblems.notWorkbook();
            }
        } catch (IOException e) {
            throw MemberProblems.notWorkbook();
        }
        try (InputStream in = file.getInputStream(); Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            Row header = sheet.getRow(sheet.getFirstRowNum());
            int cEmail = -1, cName = -1, cClass = -1;
            if (header != null) {
                for (Cell c : header) {
                    String h = fmt.formatCellValue(c).strip().toLowerCase(Locale.ROOT);
                    switch (h) {
                        case "email" -> cEmail = c.getColumnIndex();
                        case "name", "họ tên", "ho ten", "full name" -> cName = c.getColumnIndex();
                        case "class", "lớp", "lop" -> cClass = c.getColumnIndex();
                        default -> { }
                    }
                }
            }
            if (cEmail < 0) {
                errors.add(new RowProblem("email", 1, "MissingColumn", "the first row must name the columns: email, name, class"));
                throw MemberProblems.importRejected(errors);
            }
            Map<String, Integer> seen = new HashMap<>();
            int last = sheet.getLastRowNum();
            for (int r = sheet.getFirstRowNum() + 1; r <= last; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String email = cell(fmt, row, cEmail).toLowerCase(Locale.ROOT);
                String name = cell(fmt, row, cName);
                String klass = cell(fmt, row, cClass);
                if (email.isEmpty() && name.isEmpty() && klass.isEmpty()) {
                    continue;
                }
                int line = r + 1;
                if (rows.size() + errors.size() >= MAX_IMPORT_ROWS) {
                    throw MemberProblems.validation(List.of(new RowProblem("file", null, "TooManyRows", "at most " + MAX_IMPORT_ROWS + " rows")));
                }
                boolean bad = false;
                if (email.isEmpty() || email.length() > 254 || !EMAIL.matcher(email).matches()) {
                    errors.add(new RowProblem("email", line, "InvalidEmail", "not a valid email address"));
                    bad = true;
                } else if (seen.putIfAbsent(email, line) != null) {
                    errors.add(new RowProblem("email", line, "DuplicateEmail", "already on row " + seen.get(email)));
                    bad = true;
                }
                if (name.length() > 120) {
                    errors.add(new RowProblem("name", line, "TooLong", "at most 120 characters"));
                    bad = true;
                }
                UUID classId = null;
                if (!klass.isEmpty()) {
                    classId = classes.get(klass.toLowerCase(Locale.ROOT));
                    if (classId == null) {
                        errors.add(new RowProblem("class", line, "UnknownClass", "no class of this organisation has that name"));
                        bad = true;
                    }
                }
                if (!bad) {
                    rows.add(new ImportRow(line, email, classId));
                }
            }
        } catch (org.springframework.web.ErrorResponseException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw MemberProblems.notWorkbook();
        }
        if (!errors.isEmpty()) {
            throw MemberProblems.importRejected(errors);
        }
        if (rows.isEmpty()) {
            throw MemberProblems.validation(List.of(new RowProblem("file", null, "Empty", "the file has no data rows")));
        }
        for (ImportRow r : rows) {
            invitations.inviteLearner(orgId, r.email(), r.classId());
        }
        return new ImportResult(rows.size(), rows.size());
    }

    private static String cell(DataFormatter fmt, Row row, int col) {
        return col < 0 || row.getCell(col) == null ? "" : fmt.formatCellValue(row.getCell(col)).strip();
    }

    // ---- join codes --------------------------------------------------------------------------------------------------------

    static String newCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Upper-case, without spaces or dashes; null when it cannot be a code (such an attempt still counts against the limiter). */
    static String normalise(String raw) {
        if (raw == null) {
            return null;
        }
        String c = raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (c.length() != CODE_LENGTH) {
            return null;
        }
        for (int i = 0; i < c.length(); i++) {
            if (ALPHABET.indexOf(c.charAt(i)) < 0) {
                return null;
            }
        }
        return c;
    }

    static byte[] hash(String normalised) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(normalised.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    JoinCodeCreated createCode(UUID orgId, CreateJoinCodeRequest req) {
        manageable(orgId);
        Instant now = clock.instant();
        if (!req.expiresAt().isAfter(now) || req.expiresAt().isAfter(now.plus(MAX_CODE_TTL))) {
            throw MemberProblems.validation(List.of(new RowProblem("expiresAt", null, "OutOfRange", "must be in the future and within a year")));
        }
        if (req.classId() != null && !repo.classInOrg(req.classId(), orgId)) {
            throw MemberProblems.validation(List.of(new RowProblem("classId", null, "UnknownClass", "not a class of this organisation")));
        }
        String code = null;
        UUID id = null;
        for (int attempt = 0; attempt < 5 && id == null; attempt++) {
            code = newCode();
            id = repo.insertCode(orgId, req.classId(), hash(code), actingUser(), req.expiresAt(), req.maxUses()).orElse(null);
        }
        if (id == null) {
            throw new IllegalStateException("could not draw an unused join code");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("orgId", orgId.toString());
        detail.put("classId", req.classId() == null ? null : req.classId().toString());
        detail.put("expiresAt", req.expiresAt().toString());
        detail.put("maxUses", req.maxUses());
        audit.record("join-code.create", "join-code", id, detail); // never the code or its digest
        return new JoinCodeCreated(code, repo.findCode(orgId, id, now).orElseThrow());
    }

    @Transactional(readOnly = true)
    List<JoinCodeView> listCodes(UUID orgId) {
        manageable(orgId);
        return repo.codes(orgId, clock.instant());
    }

    @Transactional
    void revokeCode(UUID orgId, UUID codeId) {
        manageable(orgId);
        Instant now = clock.instant();
        JoinCodeView v = repo.revokeCode(orgId, codeId, now).orElseThrow(MemberProblems::notFound);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("orgId", orgId.toString());
        detail.put("expiresAt", v.expiresAt().toString());
        detail.put("maxUses", v.maxUses());
        detail.put("usedCount", v.usedCount());
        audit.record("join-code.revoke", "join-code", codeId, detail);
    }

    /** The caller's rate limit has already been taken (see MemberController). */
    @Transactional
    JoinResponse join(String rawCode) {
        actingUser();
        String code = normalise(rawCode);
        if (code == null) {
            throw MemberProblems.joinCodeInvalid();
        }
        MemberRepository.Redeemed r = repo.redeem(hash(code), clock.instant());
        return switch (r.outcome()) {
            case "JOINED" -> {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("classId", r.classId() == null ? null : r.classId().toString());
                detail.put("joinCodeId", r.codeId().toString());
                audit.record("org.join-code-redeem", "organization", r.orgId(), detail);
                yield new JoinResponse("joined", r.orgId());
            }
            case "ALREADY_MEMBER" -> new JoinResponse("already-member", r.orgId());
            case "EMAIL_NOT_VERIFIED" -> throw new EmailNotVerifiedException();
            default -> throw MemberProblems.joinCodeInvalid();
        };
    }

    // ---- leaving and removal -----------------------------------------------------------------------------------------------

    /** A learner leaving themselves, or an ORG_ADMIN / SYS_ADMIN removing somebody; the database function decides who may. */
    @Transactional
    void remove(UUID orgId, UUID userId) {
        UUID actor = actingUser();
        MemberRepository.Removal r = repo.remove(orgId, userId, clock.instant());
        switch (r.outcome()) {
            case "REMOVED" -> {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("orgId", orgId.toString());
                detail.put("self", actor.equals(userId));
                detail.put("roles", r.roles());
                detail.put("seatIds", r.seatIds().stream().map(UUID::toString).toList());
                detail.put("classIds", r.classIds().stream().map(UUID::toString).toList());
                audit.record("member.remove", "user", userId, detail);
            }
            case "NOT_FOUND" -> throw MemberProblems.notFound();
            case "LAST_ADMIN" -> throw MemberProblems.lastOrgAdmin();
            default -> throw new AccessDeniedException("not allowed to remove this member");
        }
    }
}
