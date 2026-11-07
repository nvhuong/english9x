package com.english9x.org.internal;

import com.english9x.identity.api.EmailNotVerifiedException;
import com.english9x.identity.api.InvitationMailer;
import com.english9x.identity.api.UserDirectory;
import com.english9x.identity.api.UserView;
import com.english9x.org.internal.OrgDtos.AcceptInvitationResponse;
import com.english9x.org.internal.OrgDtos.InvitationView;
import com.english9x.org.internal.OrgDtos.InviteRequest;
import com.english9x.org.internal.OrgDtos.OrgType;
import com.english9x.org.internal.OrgDtos.OrgView;
import com.english9x.org.internal.OrgDtos.RoleSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Invite by email and accept with the mailed token.
 *
 * <ul>
 *   <li>The token is 256 random bits; only its SHA-256 digest is stored; it exists in cleartext only in the mail, which is
 *       sent after the commit. It is single use (the accept function locks the row) and expires after
 *       {@code e9x.org.invitation-ttl}.
 *   <li>The answer to an invitation never says whether the address already has an account.
 *   <li>A newer invitation to the same address and organisation retires the older pending one.
 *   <li>Accepting: the signed-in account must be the invited address (case-insensitive) with a verified mailbox. A different
 *       account gets 403 {@code invitation-email-mismatch} and the invitation stays usable for the right one; an unverified
 *       one gets 403 {@code email-not-verified}. Decision recorded in the P1.5 report.
 * </ul>
 */
@Service
class InvitationService {

    private final OrgRepository repo;
    private final OrgAccess access;
    private final OrgProperties props;
    private final Clock clock;
    private final InvitationMailer mailer;
    private final UserDirectory users;

    InvitationService(OrgRepository repo, OrgAccess access, OrgProperties props, Clock clock, InvitationMailer mailer, UserDirectory users) {
        this.repo = repo;
        this.access = access;
        this.props = props;
        this.clock = clock;
        this.mailer = mailer;
        this.users = users;
    }

    /** @param orgId null = an English9x (internal) invitation, SYS_ADMIN only */
    @Transactional
    public InvitationView invite(UUID orgId, InviteRequest req) {
        OrgView org = null;
        Optional<OrgType> type = Optional.empty();
        if (orgId == null) {
            access.requireSysAdmin();
        } else {
            org = access.manageable(orgId);
            type = Optional.of(org.type());
        }
        List<RoleSpec> roles = RoleRules.check(type, req.roles());
        String email = req.email().strip().toLowerCase(Locale.ROOT);
        UUID inviter = access.actingUser();
        Instant now = clock.instant();

        repo.lockInviter(inviter);
        if (repo.invitationsSentSince(inviter, now.minus(Duration.ofHours(1))) >= props.invitationHourlyLimit()) {
            throw OrgProblems.tooManyInvitations(3600);
        }
        repo.retirePending(orgId, email, now);
        String token = InviteTokens.newToken();
        Instant expires = now.plus(props.invitationTtl());
        UUID id = repo.insertInvitation(orgId, email, roles, InviteTokens.hash(token), inviter, expires);
        // Audit (P1.9) must record this: actor, organisation (or English9x), invited address, roles.

        UserView who = users.findById(inviter).orElse(null);
        String orgName = org == null ? null : org.name();
        List<String> labels = roles.stream().map(r -> r.examCode() == null ? r.role().name() : r.role().name() + "/" + r.examCode()).toList();
        afterCommit(() -> mailer.sendInvitation(email, who == null ? "vi" : who.locale(), who == null ? null : who.displayName(), orgName, labels, token));
        return new InvitationView(id, email, roles, OrgDtos.InvitationStatus.PENDING, now, expires);
    }

    @Transactional(readOnly = true)
    public List<InvitationView> list(UUID orgId) {
        if (orgId == null) {
            access.requireSysAdmin();
        } else {
            access.manageable(orgId);
        }
        return repo.invitations(orgId, clock.instant());
    }

    @Transactional
    public void revoke(UUID orgId, UUID invitationId) {
        if (orgId == null) {
            access.requireSysAdmin();
        } else {
            access.manageable(orgId);
        }
        if (!repo.revoke(invitationId, orgId, clock.instant())) {
            throw OrgProblems.notFound();
        }
    }

    @Transactional
    public AcceptInvitationResponse accept(String token) {
        if (!InviteTokens.plausible(token)) {
            throw OrgProblems.invitationInvalid();
        }
        access.actingUser();
        OrgRepository.Accepted result = repo.accept(InviteTokens.hash(token), clock.instant());
        return switch (result.outcome()) {
            case "ACCEPTED" -> new AcceptInvitationResponse("accepted", result.orgId());
            case "EMAIL_MISMATCH" -> throw OrgProblems.invitationEmailMismatch();
            case "EMAIL_NOT_VERIFIED" -> throw new EmailNotVerifiedException();
            default -> throw OrgProblems.invitationInvalid();
        };
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }
}
