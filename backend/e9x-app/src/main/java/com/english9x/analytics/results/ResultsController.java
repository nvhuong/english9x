package com.english9x.analytics.results;

import com.english9x.analytics.results.ResultsDtos.AttemptResult;
import com.english9x.analytics.results.ResultsDtos.Weakness;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The learner's results: their own attempts only (another person's attempt is a 404), and the solution side only for an attempt that was scored. */
@RestController
@RequestMapping("/api/results")
class ResultsController {

    private final ResultsService service;

    ResultsController(ResultsService service) {
        this.service = service;
    }

    @GetMapping("/attempts/{id}")
    @Operation(summary = "The result of one of my attempts",
            description = "Scores per skill labelled PRELIMINARY or OFFICIAL, every question (right or wrong, the answer key, the explanation, the transcript), Writing/Speaking criteria and comments, "
                    + "the interrupted flag. 409 while the attempt is not submitted; state SCORING (nothing else) until the automatic scoring has run; 404 for an attempt that is not mine.")
    ResponseEntity<AttemptResult> attempt(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.attempt(id));
    }

    @GetMapping("/weakness")
    @Operation(summary = "My weak question types", description = "The share of right answers per question type over my scored attempts (of one exam when given), worst first, "
            + "and the method lessons of my own courses that teach the weakest ones.")
    ResponseEntity<Weakness> weakness(@RequestParam(required = false) String exam) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.weakness(exam));
    }
}
