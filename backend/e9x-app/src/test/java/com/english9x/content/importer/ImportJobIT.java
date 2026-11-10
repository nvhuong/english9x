package com.english9x.content.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.catalog.api.QuestionTypes;
import com.english9x.support.TestAuth;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * The Excel import end to end: a real application, a real PostgreSQL with the production role split, a real MinIO for the staged files and the media, the
 * real background worker (polling every 300 ms) and the real question service. Rows are limited to 5 here so "too many rows" is cheap.
 */
class ImportJobIT extends ImportIT {

    private static final String P5 = "TOEIC_P5_INCOMPLETE_SENTENCE";
    private static final String P1 = "TOEIC_P1_PHOTO";

    @DynamicPropertySource
    static void importer(DynamicPropertyRegistry registry) {
        registry.add("e9x.content.import.max-rows", () -> "5");
        registry.add("e9x.jobs.poll-interval", () -> "300ms");
        registry.add("e9x.jobs.excel_import.retry-delays", () -> ""); // no retry: a failure is final at once (the removed-member test needs it)
    }

    @Autowired QuestionTypes types;

    // ---- fixtures -------------------------------------------------------------------------------------------------------------------

    private static Map<String, String> p5(String stem, String answer) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("stem", stem);
        for (int i = 0; i < 4; i++) {
            String k = String.valueOf((char) ('A' + i));
            m.put("options." + (i + 1) + ".key", k);
            m.put("options." + (i + 1) + ".text", "text " + k);
        }
        if (answer != null) {
            m.put("answer.slots.1", answer);
        }
        return m;
    }

    private static Map<String, String> p1(String image, String audio, String answer) {
        Map<String, String> m = p5("", answer);
        m.remove("stem");
        if (image != null) {
            m.put("image_media_file", image);
        }
        if (audio != null) {
            m.put("audio_media_file", audio);
        }
        return m;
    }

    /** A workbook as an author would fill the template of the type: its own headers, one map of cells per row. */
    private byte[] workbook(String typeCode, List<Map<String, String>> rows) {
        List<String> headers = TemplateGenerator.headersOf(types.get(typeCode));
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("Du_lieu");
            Row head = s.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                head.createCell(c).setCellValue(headers.get(c));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = s.createRow(r + 1);
                for (int c = 0; c < headers.size(); c++) {
                    String v = rows.get(r).get(headers.get(c));
                    if (v != null) {
                        row.createCell(c).setCellValue(v);
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] zip(Map<String, byte[]> files) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream z = new ZipOutputStream(out)) {
            for (var e : files.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
            z.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Res startImport(UUID user, UUID org, String typeCode, byte[] xlsx, byte[] mediaZip) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        h.setBearerAuth(TestAuth.accessToken(user));
        if (org != null) {
            h.add("X-Org-Context", org.toString());
        }
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(new ByteArrayResource(xlsx) {
            @Override
            public String getFilename() {
                return "fill-in.xlsx";
            }
        }, partHeaders("file", "fill-in.xlsx")));
        if (mediaZip != null) {
            body.add("mediaZip", new HttpEntity<>(new ByteArrayResource(mediaZip) {
                @Override
                public String getFilename() {
                    return "media.zip";
                }
            }, partHeaders("mediaZip", "media.zip")));
        }
        return toRes(http.exchange("/api/cms/import?typeCode=" + typeCode, HttpMethod.POST, new HttpEntity<>(body, h), String.class));
    }

    private static HttpHeaders partHeaders(String name, String filename) {
        HttpHeaders part = new HttpHeaders();
        part.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        part.setContentDispositionFormData(name, filename);
        return part;
    }

    /** Polls the import until the job has finished with it. */
    private JsonNode awaitDone(UUID id, UUID user, UUID org) {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            Res r = get("/api/cms/import/" + id, user, org);
            assertEquals(200, r.status(), r.raw());
            String status = r.body().path("job").path("status").asText();
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) {
                return r.body();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("the import did not finish: " + su.queryForMap("select status, failure_reason from import_jobs where id = ?", id));
    }

    private UUID started(Res r) {
        assertEquals(202, r.status(), r.raw());
        assertEquals("QUEUED", r.body().path("status").asText());
        return UUID.fromString(r.body().path("jobId").asText());
    }

    private int questionVersions() {
        return su.queryForObject("select count(*) from question_versions", Integer.class);
    }

    private int bundles() {
        return su.queryForObject("select count(*) from review_bundles", Integer.class);
    }

    private static boolean hasError(JsonNode detail, int row, String column, String messagePart) {
        for (JsonNode e : detail.path("errors")) {
            if (e.path("row").asInt() == row && (column == null || column.equals(e.path("column").asText(null))) && e.path("message").asText().contains(messagePart)) {
                return true;
            }
        }
        return false;
    }

    // ---- the tests ------------------------------------------------------------------------------------------------------------------

    @Test
    void successCreatesDraftVersionsInOneJob() {
        UUID[] a = authorIn("TOEIC");
        int versionsBefore = questionVersions();
        int bundlesBefore = bundles();
        byte[] xlsx = workbook(P5, List.of(p5("First ------- sentence.", "A"), p5("Second ------- sentence.", "B"), p5("Third ------- sentence.", "C")));

        UUID id = started(startImport(a[1], a[0], P5, xlsx, null));
        JsonNode detail = awaitDone(id, a[1], a[0]);

        assertEquals("SUCCEEDED", detail.path("job").path("status").asText(), detail.toString());
        assertEquals(3, detail.path("job").path("createdCount").asInt());
        assertEquals(3, detail.path("job").path("totalRows").asInt());
        assertEquals(0, detail.path("errors").size());
        assertEquals(3, detail.path("versionIds").size());
        assertEquals(versionsBefore + 3, questionVersions());
        // every one is a DRAFT of the author, in the author's context, made from its row (in row order)
        List<Map<String, Object>> rows = su.queryForList("select v.status, v.created_by, v.owner_org_id, v.type_code, v.content->>'stem' as stem, j.row_no "
                + "from import_job_versions j join question_versions v on v.id = j.question_version_id where j.import_job_id = ? order by j.position", id);
        assertEquals(3, rows.size());
        for (Map<String, Object> r : rows) {
            assertEquals("DRAFT", r.get("status"));
            assertEquals(a[1], r.get("created_by"));
            assertEquals(a[0], r.get("owner_org_id"));
            assertEquals(P5, r.get("type_code"));
        }
        assertEquals(List.of(2, 3, 4), rows.stream().map(r -> r.get("row_no")).toList());
        assertEquals("First ------- sentence.", rows.get(0).get("stem"));
        // ONE job did all of it, and there is NO review bundle until the author submits the lot
        assertEquals(1, su.queryForObject("select count(*) from jobs where type = 'excel_import' and payload->>'importId' = ?", Integer.class, id.toString()));
        assertEquals(bundlesBefore, bundles());
        assertEquals(0, su.queryForObject("select count(*) from review_requests where version_id in (select question_version_id from import_job_versions where import_job_id = ?)",
                Integer.class, id));
        // the staged inputs are gone
        assertTrue(storedObjects().keySet().stream().noneMatch(k -> k.contains(id.toString())), "no staged file of this import is left: " + storedObjects().keySet());

        // submitting the lot makes the bundle then, and only then
        Res submitted = post("/api/cms/import/" + id + "/submit", null, a[1], a[0]);
        assertEquals(202, submitted.status(), submitted.raw());
        assertNotNull(submitted.body().path("bundleId").asText(null));
        assertEquals(bundlesBefore + 1, bundles());
        assertEquals(3, su.queryForObject("select count(*) from question_versions v join import_job_versions j on j.question_version_id = v.id "
                + "where j.import_job_id = ? and v.status = 'IN_REVIEW'", Integer.class, id));
    }

    @Test
    void anyErrorCreatesNothing() throws Exception {
        UUID[] a = authorIn("TOEIC");
        int versionsBefore = questionVersions();
        int mediaBefore = mediaRows();
        // row 3 is good, row 4 has no answer; both name files that ARE in the zip
        byte[] zip = zip(Map.of("photo.png", file("pixel.png"), "voice.mp3", file("tone.mp3")));
        byte[] xlsx = workbook(P1, List.of(p1("photo.png", "voice.mp3", "A"), p1("photo.png", "voice.mp3", null), p1("photo.png", "voice.mp3", "B")));

        UUID id = started(startImport(a[1], a[0], P1, xlsx, zip));
        JsonNode detail = awaitDone(id, a[1], a[0]);

        assertEquals("REJECTED", detail.path("job").path("status").asText(), detail.toString());
        assertTrue(hasError(detail, 3, "answer.slots.1", "thiếu đáp án"), detail.path("errors").toString());
        assertEquals(1, detail.path("errors").size(), detail.path("errors").toString());
        assertEquals(1, detail.path("job").path("errorCount").asInt());
        assertEquals(0, detail.path("job").path("createdCount").asInt());
        // NOTHING was created: no question, no media row, no media object, no link
        assertEquals(versionsBefore, questionVersions());
        assertEquals(mediaBefore, mediaRows());
        assertEquals(0, su.queryForObject("select count(*) from import_job_versions where import_job_id = ?", Integer.class, id));
        assertTrue(storedObjects().keySet().stream().noneMatch(k -> !k.contains("report") && k.contains(id.toString())), storedObjects().keySet().toString());

        // the marked workbook is there: the failed row is red and says why
        assertTrue(detail.path("job").path("hasReport").asBoolean());
        org.springframework.http.ResponseEntity<byte[]> report = http.exchange("/api/cms/import/" + id + "/report.xlsx", HttpMethod.GET,
                new HttpEntity<>(null, auth(a[1], a[0])), byte[].class);
        assertEquals(200, report.getStatusCode().value());
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(report.getBody()))) {
            Sheet s = wb.getSheet("Du_lieu");
            Row failed = s.getRow(2); // sheet row 3
            int answerColumn = TemplateGenerator.headersOf(types.get(P1)).indexOf("answer.slots.1");
            assertEquals(org.apache.poi.ss.usermodel.IndexedColors.RED.getIndex(), failed.getCell(answerColumn, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).getCellStyle().getFillForegroundColor());
            Row ok = s.getRow(1);
            assertTrue(ok.getCell(0) == null || ok.getCell(0).getCellStyle().getFillForegroundColor() != org.apache.poi.ss.usermodel.IndexedColors.RED.getIndex());
            int messages = failed.getLastCellNum() - 1;
            assertTrue(failed.getCell(messages).getStringCellValue().contains("thiếu đáp án"));
        }
        // an import of somebody else is not there
        UUID other = user("other");
        grant(other, a[0], "TOEIC", "AUTHOR");
        assertEquals(404, get("/api/cms/import/" + id, other, a[0]).status());
        assertEquals(404, get("/api/cms/import/" + id + "/report.xlsx", other, a[0]).status());
    }

    @Test
    void missingMediaFileReported() {
        UUID[] a = authorIn("TOEIC");
        int versionsBefore = questionVersions();
        int mediaBefore = mediaRows();
        byte[] zip = zip(Map.of("photo.png", file("pixel.png"), "voice.mp3", file("tone.mp3"), "notaudio.mp3", file("pixel.png")));
        byte[] xlsx = workbook(P1, List.of(
                p1("photo.png", "voice.mp3", "A"),            // row 2: fine
                p1("absent.png", "voice.mp3", "A"),           // row 3: the image is not in the zip
                p1("photo.png", "notaudio.mp3", "A"),         // row 4: it is in the zip but it is a PNG, not audio
                p1("photo.png", "voice.mp3", "A")));          // row 5: fine
        UUID id = started(startImport(a[1], a[0], P1, xlsx, zip));
        JsonNode detail = awaitDone(id, a[1], a[0]);

        assertEquals("REJECTED", detail.path("job").path("status").asText(), detail.toString());
        assertTrue(hasError(detail, 3, "image_media_file", "absent.png"), detail.path("errors").toString());
        assertTrue(hasError(detail, 4, "audio_media_file", "notaudio.mp3"), detail.path("errors").toString());
        assertEquals(2, detail.path("errors").size(), detail.path("errors").toString());
        assertEquals(versionsBefore, questionVersions());
        assertEquals(mediaBefore, mediaRows());

        // no zip at all: every row that names a file says so
        UUID noZip = started(startImport(a[1], a[0], P1, workbook(P1, List.of(p1("photo.png", "voice.mp3", "A"))), null));
        JsonNode d2 = awaitDone(noZip, a[1], a[0]);
        assertEquals("REJECTED", d2.path("job").path("status").asText());
        assertTrue(hasError(d2, 2, "image_media_file", "zip"), d2.path("errors").toString());
        assertEquals(mediaBefore, mediaRows());
    }

    @Test
    void mediaInTheZipBecomesMediaOfTheDrafts() {
        UUID[] a = authorIn("TOEIC");
        int mediaBefore = mediaRows();
        byte[] zip = zip(Map.of("sub/photo.png", file("pixel.png"), "voice.mp3", file("tone.mp3")));
        UUID id = started(startImport(a[1], a[0], P1, workbook(P1, List.of(p1("photo.png", "voice.mp3", "A"), p1("photo.png", "voice.mp3", "C"))), zip));
        JsonNode detail = awaitDone(id, a[1], a[0]);
        assertEquals("SUCCEEDED", detail.path("job").path("status").asText(), detail.toString());
        assertEquals(mediaBefore + 2, mediaRows(), "one media row per distinct file, however many rows use it");
        List<Map<String, Object>> rows = su.queryForList("select v.content->>'imageMediaId' as image, v.content->>'audioMediaId' as audio from import_job_versions j "
                + "join question_versions v on v.id = j.question_version_id where j.import_job_id = ?", id);
        assertEquals(2, rows.size());
        assertEquals(rows.get(0).get("image"), rows.get(1).get("image"));
        for (Map<String, Object> r : rows) {
            assertEquals(1, su.queryForObject("select count(*) from media_assets where id = ? and kind = 'IMAGE' and owner_org_id = ? and exam_code = 'TOEIC'", Integer.class,
                    UUID.fromString((String) r.get("image")), a[0]));
            assertEquals(1, su.queryForObject("select count(*) from media_assets where id = ? and kind = 'AUDIO'", Integer.class, UUID.fromString((String) r.get("audio"))));
        }
    }

    @Test
    void overRowLimitRejected() {
        UUID[] a = authorIn("TOEIC");
        int versionsBefore = questionVersions();
        List<Map<String, String>> six = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) { // e9x.content.import.max-rows is 5 in this test
            six.add(p5("Sentence " + i + " -------.", "A"));
        }
        UUID id = started(startImport(a[1], a[0], P5, workbook(P5, six), null));
        JsonNode detail = awaitDone(id, a[1], a[0]);
        assertEquals("REJECTED", detail.path("job").path("status").asText(), detail.toString());
        assertTrue(hasError(detail, 0, null, "5 dòng"), detail.path("errors").toString());
        assertEquals(versionsBefore, questionVersions());
        // exactly at the limit is fine
        UUID ok = started(startImport(a[1], a[0], P5, workbook(P5, six.subList(0, 5)), null));
        assertEquals("SUCCEEDED", awaitDone(ok, a[1], a[0]).path("job").path("status").asText());
    }

    @Test
    void exhaustedImportOfARemovedMemberIsMarkedFailedWithoutTheirBinding() {
        UUID[] a = authorIn("TOEIC");
        UUID orgId = a[0];
        UUID author = a[1];
        // an import that is queued, then the author is removed from the organisation before the worker gets to it
        UUID id = UUID.randomUUID();
        su.update("insert into import_jobs(id, owner_org_id, user_id, exam_code, type_code, source_name) values (?,?,?,?,?,?)", id, orgId, author, "TOEIC", P5, "x.xlsx");
        su.update("delete from role_grants where user_id = ? and org_id = ?", author, orgId);
        su.update("delete from org_memberships where user_id = ? and org_id = ?", author, orgId);
        su.update("insert into jobs(id, type, payload, status, run_at) values (gen_random_uuid(), 'excel_import', ?::jsonb, 'QUEUED', now())",
                "{\"importId\":\"" + id + "\",\"userId\":\"" + author + "\",\"orgId\":\"" + orgId + "\"}");

        long deadline = System.currentTimeMillis() + 60_000;
        String status = "";
        while (System.currentTimeMillis() < deadline) {
            status = su.queryForObject("select status from import_jobs where id = ?", String.class, id);
            if (status.equals("FAILED")) {
                break;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        assertEquals("FAILED", status, "the import must not stick in " + status);
        Map<String, Object> row = su.queryForMap("select failure_reason, finished_at from import_jobs where id = ?", id);
        assertNotNull(row.get("finished_at"));
        assertEquals("the import could not be run", row.get("failure_reason"));
        assertEquals("FAILED", su.queryForObject("select status from jobs where payload->>'importId' = ?", String.class, id.toString()));
    }

    @Test
    void onlyAnAuthorOfTheExamImportsAndOnlyItsTypes() {
        UUID[] a = authorIn("IELTS");
        byte[] xlsx = workbook(P5, List.of(p5("Sentence -------.", "A")));
        assertEquals(403, startImport(a[1], a[0], P5, xlsx, null).status(), "an IELTS author cannot import a TOEIC type");
        assertEquals(403, get("/api/cms/import/templates/" + P5 + ".xlsx", a[1], a[0]).status());
        UUID learner = user("learner");
        grant(learner, a[0], null, "LEARNER");
        assertEquals(403, startImport(learner, a[0], "IELTS_R_MATCHING_HEADINGS", xlsx, null).status());
        assertEquals(422, startImport(a[1], a[0], "NOPE", xlsx, null).status());
        // not a workbook
        Res notXlsx = startImport(a[1], a[0], "IELTS_R_MATCHING_HEADINGS", "hello".getBytes(), null);
        assertEquals(422, notXlsx.status(), notXlsx.raw());
        assertEquals("urn:e9x:problem:import-not-xlsx", notXlsx.problemType());
    }

    @Test
    void theTemplateDownloads() {
        UUID[] a = authorIn("TOEIC");
        org.springframework.http.ResponseEntity<byte[]> r = http.exchange("/api/cms/import/templates/" + P5 + ".xlsx", HttpMethod.GET, new HttpEntity<>(null, auth(a[1], a[0])), byte[].class);
        assertEquals(200, r.getStatusCode().value());
        assertTrue(r.getHeaders().getContentDisposition().getFilename().endsWith(P5 + ".xlsx"));
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(r.getBody()))) {
            assertEquals("Huong_dan", wb.getSheetName(0));
            assertEquals("Du_lieu", wb.getSheetName(1));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theTemplateExampleRowIsSkippedAndAFilledTemplateImports() throws IOException {
        UUID[] a = authorIn("TOEIC");
        org.springframework.http.ResponseEntity<byte[]> t = http.exchange("/api/cms/import/templates/" + P5 + ".xlsx", HttpMethod.GET, new HttpEntity<>(null, auth(a[1], a[0])), byte[].class);
        // the untouched template holds only the marked example row: there is nothing to import
        UUID id = started(startImport(a[1], a[0], P5, t.getBody(), null));
        JsonNode detail = awaitDone(id, a[1], a[0]);
        assertEquals("REJECTED", detail.path("job").path("status").asText());
        assertTrue(hasError(detail, 0, null, "không có dòng dữ liệu"), detail.path("errors").toString());
        assertFalse(detail.path("job").path("hasReport").asBoolean());
    }

    private HttpHeaders auth(UUID user, UUID org) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(TestAuth.accessToken(user));
        if (org != null) {
            h.add("X-Org-Context", org.toString());
        }
        return h;
    }
}
