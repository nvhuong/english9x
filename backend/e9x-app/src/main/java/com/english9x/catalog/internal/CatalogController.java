package com.english9x.catalog.internal;

import com.english9x.catalog.api.ExamInfo;
import com.english9x.catalog.api.Exams;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The exams of the catalog, for every signed-in person: where the SPA lets somebody choose an exam it asks here, not a constant of its own. */
@RestController
class CatalogController {

    private final Exams exams;

    CatalogController(Exams exams) {
        this.exams = exams;
    }

    @GetMapping("/api/catalog/exams")
    List<ExamInfo> exams() {
        return exams.all();
    }
}
