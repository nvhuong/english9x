package com.english9x.org.members;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.org.OrgIT;
import com.english9x.support.TestAuth;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** F44: inviting many learners, by a list of addresses or an Excel file whose bad rows are reported by row and nobody is invited until it is clean. */
class MemberImportIT extends OrgIT {

    private static byte[] workbook(String[]... rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("members");
            String[] header = {"email", "name", "class"};
            for (int r = -1; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                String[] cells = r < 0 ? header : rows[r];
                for (int c = 0; c < cells.length; c++) {
                    row.createCell(c).setCellValue(cells[c]);
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private Res upload(UUID org, UUID admin, byte[] bytes) {
        return upload(org, admin, org, bytes);
    }

    private Res upload(UUID org, UUID admin, UUID context, byte[] bytes) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(TestAuth.accessToken(admin));
        h.set("X-Org-Context", context.toString());
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "members.xlsx";
            }
        });
        return toRes(http.exchange("/api/orgs/" + org + "/members/import", HttpMethod.POST, new HttpEntity<>(body, h), String.class));
    }

    @Test
    void reportsRowErrors() throws Exception {
        UUID org = org("CENTER");
        UUID admin = orgAdmin(org);
        UUID klass = UUID.randomUUID();
        su.update("insert into classes(id,owner_org_id,name) values (?,?,?)", klass, org, "Lop A");
        String good = email("good");
        String also = email("also");
        byte[] bad = workbook(
                new String[] {good, "Nguyen A", "Lop A"},
                new String[] {"not-an-email", "B", ""},
                new String[] {good, "Dup", ""},
                new String[] {email("x"), "C", "No Such Class"});
        Res r = upload(org, admin, bad);
        assertEquals(422, r.status(), r.raw());
        assertEquals("urn:e9x:problem:import-has-errors", r.problemType());
        JsonNodes errs = new JsonNodes(r);
        assertEquals(List.of("3:InvalidEmail", "4:DuplicateEmail", "5:UnknownClass"), errs.rowCodes(), r.raw());
        assertEquals(0, su.queryForObject("select count(*) from invitations where org_id = ?", Integer.class, org), "all rows or none");

        Res ok = upload(org, admin, workbook(new String[] {good, "Nguyen A", "Lop A"}, new String[] {also, "Tran B", ""}));
        assertEquals(200, ok.status(), ok.raw());
        assertEquals(2, ok.body().path("invited").asInt());
        assertEquals(1, su.queryForObject("select count(*) from invitations where org_id = ? and email = ? and class_id = ?", Integer.class, org, good, klass));
        assertEquals(1, su.queryForObject("select count(*) from audit_log where action = 'invitation.send' and detail->>'orgId' = ?", Integer.class, org.toString()) - 1);

        // accepting the mailed link puts the learner in the class too
        String link = TestAuth.SMTP.await(good).linkParam("token");
        UUID person = user(good, true);
        assertEquals(200, post("/api/invitations/" + link + "/accept", null, person, null).status());
        assertEquals(1, su.queryForObject("select count(*) from class_members where class_id = ? and user_id = ?", Integer.class, klass, person));

        // not a workbook, and not for a learner
        assertEquals(400, upload(org, admin, "hello".getBytes()).status());
        UUID learner = user("plain");
        member(org, learner);
        assertEquals(403, upload(org, learner, workbook(new String[] {email("y"), "", ""})).status());
        UUID otherOrg = org("CENTER");
        UUID otherAdmin = orgAdmin(otherOrg);
        assertEquals(404, upload(org, otherAdmin, otherOrg, workbook(new String[] {email("z"), "", ""})).status(), "another organisation's admin");
    }

    @Test
    void bulkInviteByAddresses() {
        UUID org = org("CENTER");
        UUID admin = orgAdmin(org);
        String a = email("a");
        String b = email("b");
        Res r = post("/api/orgs/" + org + "/members/invite", Map.of("emails", List.of(a, b.toUpperCase(), a)), admin, org);
        assertEquals(200, r.status(), r.raw());
        assertEquals(2, r.body().path("invited").asInt(), "the repeated address is invited once");
        assertTrue(TestAuth.SMTP.await(a).subject().length() > 0);
        assertEquals(400, post("/api/orgs/" + org + "/members/invite", Map.of("emails", List.of("nope")), admin, org).status());
        assertEquals(400, post("/api/orgs/" + org + "/members/invite", Map.of("emails", List.of()), admin, org).status());
    }

    /** rows of errors[] as "row:code" */
    private record JsonNodes(Res r) {
        List<String> rowCodes() {
            List<String> out = new java.util.ArrayList<>();
            r.body().path("errors").forEach(e -> out.add(e.path("row").asInt() + ":" + e.path("code").asText()));
            return out;
        }
    }
}
