package com.english9x.workflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.english9x.platform.web.ProblemDetailsAdvice;
import com.english9x.workflow.api.InvalidReviewInputException;
import com.english9x.workflow.api.ReviewConflictException;
import com.english9x.workflow.api.ReviewNotFoundException;
import com.english9x.workflow.api.UnpublishedDependencyException;
import com.english9x.workflow.api.VersionRef;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The workflow exceptions leave the API as problem+json with the right status and type, through the real advice. */
class WorkflowProblemsTest {

    static final UUID DEP = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @RestController
    static class Thrower {
        @GetMapping("/nf")
        void nf() {
            throw new ReviewNotFoundException();
        }

        @GetMapping("/conflict")
        void conflict() {
            throw new ReviewConflictException("the review is already decided");
        }

        @GetMapping("/deps")
        void deps() {
            throw new UnpublishedDependencyException(List.of(new VersionRef("item", DEP)));
        }

        @GetMapping("/invalid")
        void invalid() {
            throw new InvalidReviewInputException("comment", "required", "a comment is required");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Thrower())
            .setControllerAdvice(new ProblemDetailsAdvice(new StaticMessageSource())).build();

    @Test
    void notFoundIs404() throws Exception {
        mvc.perform(get("/nf")).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:e9x:problem:review-not-found"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void conflictIs409() throws Exception {
        mvc.perform(get("/conflict")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:e9x:problem:review-conflict"))
                .andExpect(jsonPath("$.detail").value("the review is already decided"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void unpublishedDependenciesIs409WithTheList() throws Exception {
        mvc.perform(get("/deps")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:e9x:problem:unpublished-dependencies"))
                .andExpect(jsonPath("$.dependencies[0].type").value("item"))
                .andExpect(jsonPath("$.dependencies[0].versionId").value(DEP.toString()))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void invalidInputIs400WithTheField() throws Exception {
        mvc.perform(get("/invalid")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:e9x:problem:validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("comment"))
                .andExpect(jsonPath("$.errors[0].code").value("required"));
    }
}
