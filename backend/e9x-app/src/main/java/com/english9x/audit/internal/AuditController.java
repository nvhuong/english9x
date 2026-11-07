package com.english9x.audit.internal;

import com.english9x.audit.internal.AuditQueryService.AuditPage;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The system administrator's audit log (F57). SYS_ADMIN only; there is deliberately no organisation-level log. */
@RestController
class AuditController {

    private final AuditQueryService service;

    AuditController(AuditQueryService service) {
        this.service = service;
    }

    /**
     * @param actor a user id or an email address
     * @param from  inclusive, ISO-8601 instant
     * @param to    exclusive, ISO-8601 instant
     * @param page  zero-based
     * @param size  at most {@code e9x.audit.max-page-size}; a larger value is answered with that many
     */
    @GetMapping("/api/admin/audit-log")
    AuditPage list(@RequestParam(required = false) String action,
                   @RequestParam(required = false) String targetType,
                   @RequestParam(required = false) String targetId,
                   @RequestParam(required = false) String actor,
                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                   @RequestParam(required = false) Integer page,
                   @RequestParam(required = false) Integer size) {
        return service.list(new AuditQueryService.Query(action, targetType, targetId, actor, from, to, page, size));
    }

    @GetMapping("/api/admin/audit-log/actions")
    List<String> actions() {
        return service.actions();
    }
}
