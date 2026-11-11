package com.english9x.org.members;

import com.english9x.identity.api.ClientAddress;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.members.MemberDtos.BulkInviteRequest;
import com.english9x.org.members.MemberDtos.BulkInviteResponse;
import com.english9x.org.members.MemberDtos.CreateJoinCodeRequest;
import com.english9x.org.members.MemberDtos.ImportResult;
import com.english9x.org.members.MemberDtos.JoinCodeCreated;
import com.english9x.org.members.MemberDtos.JoinCodeView;
import com.english9x.org.members.MemberDtos.JoinResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * F44. {@code POST /api/join/{code}} is rate limited here, before anything else happens: per client address AND per signed-in account (8 characters
 * of a 32-symbol alphabet is guessable by a patient script, unlike a 256-bit invitation token). Every attempt takes one from both allowances; a
 * join gives them back, so only wrong guesses (and expired / used-up / unverified attempts) count.
 */
@RestController
class MemberController {

    private final MemberService members;
    private final ClientAddress clientAddress;
    private final JoinAttemptLimiter ipLimiter;
    private final JoinAttemptLimiter userLimiter;

    MemberController(MemberService members, ClientAddress clientAddress, MemberProperties props, Clock clock) {
        this.members = members;
        this.clientAddress = clientAddress;
        this.ipLimiter = new JoinAttemptLimiter(props.ipRateLimit(), clock, 20_000);
        this.userLimiter = new JoinAttemptLimiter(props.userRateLimit(), clock, 20_000);
    }

    @PostMapping("/api/orgs/{id}/members/invite")
    BulkInviteResponse invite(@PathVariable UUID id, @Valid @RequestBody BulkInviteRequest body) {
        return members.invite(id, body.emails());
    }

    @Schema(name = "MemberImportForm")
    record ImportForm(@Schema(type = "string", format = "binary", description = "the .xlsx with the columns email, name, class") String file) {}

    @PostMapping(path = "/api/orgs/{id}/members/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Invite the learners of an Excel file (all rows or none; errors by row)",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = "multipart/form-data", schema = @Schema(implementation = ImportForm.class))))
    ImportResult importMembers(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        return members.importWorkbook(id, file);
    }

    @PostMapping("/api/orgs/{id}/join-codes")
    @Operation(summary = "Create a join code; the code is in this answer only")
    ResponseEntity<JoinCodeCreated> createCode(@PathVariable UUID id, @Valid @RequestBody CreateJoinCodeRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(members.createCode(id, body));
    }

    @GetMapping("/api/orgs/{id}/join-codes")
    List<JoinCodeView> codes(@PathVariable UUID id) {
        return members.listCodes(id);
    }

    @DeleteMapping("/api/orgs/{id}/join-codes/{codeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revokeCode(@PathVariable UUID id, @PathVariable UUID codeId) {
        members.revokeCode(id, codeId);
    }

    @PostMapping("/api/join/{code}")
    @Operation(summary = "Join an organisation with a code (410 when not valid, 429 when guessing too fast)")
    ResponseEntity<JoinResponse> join(@PathVariable String code, HttpServletRequest request) {
        String ip = clientAddress.of(request);
        String user = CurrentContextHolder.current().map(b -> b.userId().toString()).orElse("anonymous");
        if (!ipLimiter.tryAcquire(ip)) {
            throw MemberProblems.tooManyAttempts(ipLimiter.retryAfterSeconds(ip));
        }
        if (!userLimiter.tryAcquire(user)) {
            ipLimiter.refund(ip);
            throw MemberProblems.tooManyAttempts(userLimiter.retryAfterSeconds(user));
        }
        JoinResponse r = members.join(code);
        ipLimiter.refund(ip);
        userLimiter.refund(user);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(r);
    }

    @DeleteMapping("/api/orgs/{id}/members/{userId}")
    @Operation(summary = "Leave the organisation (own id) or remove a member (ORG_ADMIN): the seat is taken back, the history stays")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable UUID id, @PathVariable UUID userId) {
        members.remove(id, userId);
    }
}
