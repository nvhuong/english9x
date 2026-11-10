package com.english9x.content.importer;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The problem+json answers of the import endpoints. */
final class ImportProblems {

    private ImportProblems() {}

    static ErrorResponseException unknownType() {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, "import-type-unknown", "Question type not importable",
                "This question type does not exist or has no Excel template.");
    }

    static ErrorResponseException fileMissing() {
        return of(HttpStatus.BAD_REQUEST, "import-file-missing", "No Excel file", "Send the filled-in workbook as the part 'file'.");
    }

    static ErrorResponseException notWorkbook() {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, "import-not-xlsx", "Not an Excel workbook", "The file must be an .xlsx workbook made from the template.");
    }

    static ErrorResponseException notZip() {
        return of(HttpStatus.UNPROCESSABLE_ENTITY, "import-not-zip", "Not a zip file", "The media file must be a .zip.");
    }

    static ErrorResponseException tooLarge(String what, String limit) {
        return of(HttpStatus.PAYLOAD_TOO_LARGE, "import-too-large", "Too large", "The " + what + " is larger than " + limit + ".");
    }

    static ErrorResponseException notFound() {
        return of(HttpStatus.NOT_FOUND, "not-found", "Not found", "There is no such import, or you may not open it.");
    }

    static ErrorResponseException notSubmittable() {
        return of(HttpStatus.CONFLICT, "import-not-submittable", "Nothing to submit",
                "Only an import that finished and made drafts can be submitted for review.");
    }

    static ErrorResponseException noReport() {
        return of(HttpStatus.NOT_FOUND, "import-no-report", "No error report", "This import has no marked-up workbook.");
    }

    static ErrorResponseException unavailable() {
        return of(HttpStatus.SERVICE_UNAVAILABLE, "import-unavailable", "Import unavailable", "File storage is not available. Try again later.");
    }

    private static ErrorResponseException of(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("urn:e9x:problem:" + slug));
        p.setTitle(title);
        return new ErrorResponseException(status, p, null);
    }
}
