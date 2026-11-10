package com.english9x.billing.license;

import com.english9x.billing.license.LicenseDtos.AssignSeatRequest;
import com.english9x.billing.license.LicenseDtos.CreateLicenseRequest;
import com.english9x.billing.license.LicenseDtos.LicenseCourse;
import com.english9x.billing.license.LicenseDtos.LicenseCreated;
import com.english9x.billing.license.LicenseDtos.LicenseView;
import com.english9x.billing.license.LicenseDtos.SeatHolder;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Organisation licences: created by the SYS_ADMIN, read and used by the organisation's ORG_ADMIN. */
@RestController
class LicenseController {

    private final LicenseService licenses;
    private final SeatService seats;

    LicenseController(LicenseService licenses, SeatService seats) {
        this.licenses = licenses;
        this.seats = seats;
    }

    /** CONTRACT: active now. QR: waits for the payment of its order (the payer is an ORG_ADMIN of the organisation). */
    @PostMapping("/api/admin/org-licenses")
    @ResponseStatus(HttpStatus.CREATED)
    LicenseCreated create(@Valid @RequestBody CreateLicenseRequest body) {
        return licenses.create(body);
    }

    @GetMapping("/api/admin/org-licenses")
    List<LicenseView> listAll() {
        return licenses.listAll();
    }

    /** The published English9x courses a licence may cover. */
    @GetMapping("/api/admin/org-licenses/courses")
    List<LicenseCourse> licensableCourses() {
        return licenses.licensableCourses();
    }

    /** The licences of the organisation of the context, with who holds each seat. */
    @GetMapping("/api/orgs/{orgId}/licenses")
    List<LicenseView> ofOrg(@PathVariable UUID orgId) {
        return licenses.ofOrg(orgId);
    }

    /** 409 when no seat is free / the person already holds one / the licence is not active; 422 for a course outside the licence or a person outside the organisation. */
    @PostMapping("/api/orgs/{orgId}/licenses/{id}/seats")
    @ResponseStatus(HttpStatus.CREATED)
    SeatHolder assign(@PathVariable UUID orgId, @PathVariable UUID id, @Valid @RequestBody AssignSeatRequest body) {
        return seats.assign(orgId, id, body.userId(), body.courseIds());
    }

    @DeleteMapping("/api/orgs/{orgId}/licenses/{id}/seats/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable UUID orgId, @PathVariable UUID id, @PathVariable UUID userId) {
        seats.revoke(orgId, id, userId);
    }
}
