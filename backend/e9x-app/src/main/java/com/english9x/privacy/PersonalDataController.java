package com.english9x.privacy;

import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Personal-data erasure (spec §15): a SYS_ADMIN erases one person's personal data (name, email, identities, invitations, notifications, recordings, essays). Audited, irreversible. */
@RestController
class PersonalDataController {

    private final PersonalDataService service;

    PersonalDataController(PersonalDataService service) {
        this.service = service;
    }

    @DeleteMapping("/api/admin/users/{id}/personal-data")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Erase a person's personal data (Nghị định 13/2023): scrubs users, identities, invitations, notifications, voice recordings, essays, feedback and transfer memos. SYS_ADMIN.")
    void erase(@PathVariable UUID id) {
        service.erase(id);
    }
}
