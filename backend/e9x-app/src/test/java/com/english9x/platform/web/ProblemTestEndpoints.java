package com.english9x.platform.web;

import com.english9x.org.api.OrgContextDeniedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Test-only endpoints that provoke every error class the advice must map. Registered only under "problem-test". */
@RestController
@Profile("problem-test")
class ProblemTestEndpoints {

    record Row(@NotBlank String sku, @Min(1) int qty) {}

    record Form(@NotBlank String name, @Email String email, @Valid List<Row> rows) {}

    @PostMapping("/problem-test/form")
    String form(@Valid @RequestBody Form form) {
        return "ok";
    }

    @GetMapping("/problem-test/boom")
    String boom() {
        throw new IllegalStateException("secret-internal-detail jdbc:postgresql://db.internal:5432/e9x password=hunter2");
    }

    @GetMapping("/problem-test/denied")
    String denied() {
        throw new OrgContextDeniedException(UUID.randomUUID(), UUID.randomUUID());
    }

    @GetMapping("/problem-test/denied-wrapped")
    String deniedWrapped() {
        throw new CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction",
                new OrgContextDeniedException(UUID.randomUUID(), UUID.randomUUID()));
    }

    @GetMapping("/problem-test/missing")
    String missing() {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such thing");
    }
}
