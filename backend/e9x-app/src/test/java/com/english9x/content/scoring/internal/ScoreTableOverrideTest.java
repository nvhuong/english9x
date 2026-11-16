package com.english9x.content.scoring.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.english9x.catalog.api.ExamInfo;
import com.english9x.catalog.api.Exams;
import com.english9x.content.scoring.api.ScoreRow;
import com.english9x.content.scoring.api.ScoreScope;
import com.english9x.content.scoring.api.ScoreTableVersionView;
import com.english9x.content.scoring.internal.ScoreTableRepository.Header;
import com.english9x.content.scoring.internal.ScoreTableRepository.TestRef;
import com.english9x.org.api.Authz;
import com.english9x.org.api.CurrentContextHolder;
import com.english9x.org.api.Role;
import com.english9x.workflow.api.ContentStatus;
import com.english9x.workflow.api.ReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponseException;

/**
 * The override of one test's conversion table (F13, F15): the administrator of the test's owner attaches its header, an author writes its versions. No database: the
 * repository is a mock (the policies and the trigger of V202611120002 are tested by db-test 44 and ScoreTableServiceIT).
 */
class ScoreTableOverrideTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private final Authz authz = mock(Authz.class);
    private final Exams exams = mock(Exams.class);
    private final ScoreTableRepository repository = mock(ScoreTableRepository.class);
    private final ScoreTableService service = new ScoreTableService(authz, exams, mock(ReviewService.class), repository);
    private CurrentContextHolder.Scope bound;
    private final UUID testId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        bound = CurrentContextHolder.bind(USER, ORG);
        when(exams.find("TOEIC")).thenReturn(Optional.of(new ExamInfo("TOEIC", "TOEIC", List.of(), List.of("LISTENING", "READING", "TOTAL"), List.of())));
        when(exams.find("IELTS")).thenReturn(Optional.of(new ExamInfo("IELTS", "IELTS", List.of("AC", "GT"),
                List.of("LISTENING", "READING_AC", "READING_GT", "WRITING", "SPEAKING", "OVERALL"), List.of())));
        when(repository.test(testId)).thenReturn(Optional.of(new TestRef(testId, ORG, "TOEIC", null)));
        when(repository.examDefault(eq("TOEIC"), eq("TOTAL"), eq(null))).thenReturn(Optional.of(new Header(UUID.randomUUID(), null, "TOEIC", "TOTAL", ScoreScope.EXAM, null, "SUM")));
        when(repository.examDefault(eq("TOEIC"), eq("LISTENING"), eq(null))).thenReturn(Optional.empty());
        when(repository.override(any(), any())).thenReturn(Optional.empty());
        when(authz.has(any(), any())).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        bound.close();
    }

    private void admin(Role role) {
        doThrow(new AccessDeniedException("no")).when(authz).require(any(), any());
        org.mockito.Mockito.doNothing().when(authz).require(eq(role), eq(Optional.of("TOEIC")));
        when(authz.has(eq(role), eq(Optional.of("TOEIC")))).thenReturn(true);
    }

    @Test
    void theAdministratorOfTheOrganisationAttachesTheHeaderOnly() {
        admin(Role.ORG_ADMIN);
        ScoreTableRepository.TestOverride o = service.attach(testId, "LISTENING");
        ArgumentCaptor<Header> header = ArgumentCaptor.forClass(Header.class);
        verify(repository).insertHeader(header.capture());
        assertEquals(new Header(o.tableId(), ORG, "TOEIC", "LISTENING", ScoreScope.TEST, testId, "TABLE"), header.getValue());
        verify(repository, never()).insertVersion(any(), any(), anyInt(), any(), any());
        assertNull(o.versionId(), "no version until an author writes it");
        assertEquals(testId, o.testId());
    }

    @Test
    void anEnglish9xTestNeedsTheSysAdmin() {
        when(repository.test(testId)).thenReturn(Optional.of(new TestRef(testId, null, "TOEIC", null)));
        admin(Role.ORG_ADMIN);
        assertThrows(AccessDeniedException.class, () -> service.attach(testId, "LISTENING"));
        admin(Role.SYS_ADMIN);
        assertEquals(null, service.attach(testId, "LISTENING").ownerOrgId());
    }

    @Test
    void anAuthorIsNotTheAdministrator() {
        doThrow(new AccessDeniedException("no")).when(authz).require(any(), any());
        org.mockito.Mockito.doNothing().when(authz).require(eq(Role.AUTHOR), any());
        assertThrows(AccessDeniedException.class, () -> service.attach(testId, "LISTENING"));
        verify(repository, never()).insertHeader(any());
    }

    @Test
    void aTestTheCallerCannotOpenIs404() {
        admin(Role.ORG_ADMIN);
        ErrorResponseException e = assertThrows(ErrorResponseException.class, () -> service.attach(UUID.randomUUID(), "LISTENING"));
        assertEquals(404, e.getStatusCode().value());
    }

    @Test
    void onlyATableScoreOfTheExamAndOfTheTestsVariant() {
        admin(Role.ORG_ADMIN);
        for (String bad : new String[] {"READING_AC", "TOTAL"}) {
            ErrorResponseException e = assertThrows(ErrorResponseException.class, () -> service.attach(testId, bad), bad);
            assertEquals(422, e.getStatusCode().value());
        }
        assertEquals(422, assertThrows(ErrorResponseException.class, () -> service.attach(null, null)).getStatusCode().value());
        UUID ielts = UUID.randomUUID();
        when(repository.test(ielts)).thenReturn(Optional.of(new TestRef(ielts, ORG, "IELTS", "AC")));
        doThrow(new AccessDeniedException("no")).when(authz).require(any(), any());
        org.mockito.Mockito.doNothing().when(authz).require(eq(Role.ORG_ADMIN), eq(Optional.of("IELTS")));
        ErrorResponseException gt = assertThrows(ErrorResponseException.class, () -> service.attach(ielts, "READING_GT"));
        assertEquals(422, gt.getStatusCode().value());
        assertTrue(gt.getBody().getProperties().get("errors").toString().contains("another variant"));
        verify(repository, never()).insertHeader(any());
    }

    @Test
    void oneOverridePerTestAndSkill() {
        admin(Role.ORG_ADMIN);
        UUID existing = UUID.randomUUID();
        when(repository.override(testId, "LISTENING")).thenReturn(Optional.of(new Header(existing, ORG, "TOEIC", "LISTENING", ScoreScope.TEST, testId, "TABLE")));
        ErrorResponseException e = assertThrows(ErrorResponseException.class, () -> service.attach(testId, "LISTENING"));
        assertEquals(409, e.getStatusCode().value());
        assertEquals(existing.toString(), e.getBody().getProperties().get("tableId"));
    }

    @Test
    void anAuthorWritesVersion1OfAnAttachedOverride() throws Exception {
        UUID table = UUID.randomUUID();
        Header header = new Header(table, ORG, "TOEIC", "LISTENING", ScoreScope.TEST, testId, "TABLE");
        when(repository.header(table)).thenReturn(Optional.of(header));
        when(repository.latest(table)).thenReturn(Optional.empty());
        ScoreTableVersionView v1 = new ScoreTableVersionView(UUID.randomUUID(), table, 1, ContentStatus.DRAFT, Optional.of(ORG), "TOEIC", "LISTENING", ScoreScope.TEST,
                Optional.of(testId), "TABLE", List.of(new ScoreRow(0, 5), new ScoreRow(1, 300)), USER, Instant.now(), Instant.now());
        when(repository.find(any())).thenReturn(Optional.of(v1));
        ScoreTableService.Saved saved = service.update(table, new ScoreTableService.Body(null, null, "TEST", testId, JSON.readTree("[{\"raw\":0,\"scaled\":5},{\"raw\":1,\"scaled\":300}]")));
        assertTrue(saved.newVersion());
        verify(authz).require(Role.AUTHOR, Optional.of("TOEIC"));
        verify(repository).insertVersion(any(), eq(header), eq(1), eq(List.of(new ScoreRow(0, 5), new ScoreRow(1, 300))), eq(USER));
    }

    @Test
    void theScopeNeverChangesAndTheTestIsItsOwn() throws Exception {
        UUID table = UUID.randomUUID();
        when(repository.header(table)).thenReturn(Optional.of(new Header(table, ORG, "TOEIC", "LISTENING", ScoreScope.TEST, testId, "TABLE")));
        when(repository.latest(table)).thenReturn(Optional.empty());
        var rows = JSON.readTree("[{\"raw\":0,\"scaled\":5}]");
        for (ScoreTableService.Body bad : List.of(new ScoreTableService.Body(null, null, "EXAM", null, rows), new ScoreTableService.Body(null, null, "TEST", UUID.randomUUID(), rows),
                new ScoreTableService.Body(null, null, null, null, rows))) {
            assertEquals(422, assertThrows(ErrorResponseException.class, () -> service.update(table, bad)).getStatusCode().value());
        }
        verify(repository, never()).insertVersion(any(), any(), anyInt(), any(), any());
        // an exam default without a version is not a thing: 404
        UUID exam = UUID.randomUUID();
        when(repository.header(exam)).thenReturn(Optional.of(new Header(exam, null, "TOEIC", "READING", ScoreScope.EXAM, null, "TABLE")));
        when(repository.latest(exam)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(ErrorResponseException.class, () -> service.update(exam, new ScoreTableService.Body(null, null, "EXAM", null, rows))).getStatusCode().value());
    }

    @Test
    void creatingWithScopeTestPointsAtTheAttachEndpoint() throws Exception {
        org.mockito.Mockito.doNothing().when(authz).require(any(), any());
        ErrorResponseException e = assertThrows(ErrorResponseException.class,
                () -> service.create(new ScoreTableService.Body("TOEIC", "LISTENING", "TEST", testId, JSON.readTree("[{\"raw\":0,\"scaled\":5}]"))));
        assertEquals(422, e.getStatusCode().value());
        assertTrue(e.getBody().getProperties().get("errors").toString().contains("/api/cms/score-tables/overrides"));
        verify(repository, never()).insertHeader(any());
    }

    @Test
    void theOverridesOfATestAreReadByItsStaffAndItsAdministrator() {
        when(repository.overrides(testId)).thenReturn(List.of());
        assertThrows(AccessDeniedException.class, () -> service.overrides(testId));
        when(authz.has(eq(Role.ORG_ADMIN), eq(Optional.of("TOEIC")))).thenReturn(true);
        assertEquals(List.of(), service.overrides(testId));
        when(authz.has(eq(Role.ORG_ADMIN), any())).thenReturn(false);
        when(authz.has(eq(Role.REVIEWER), eq(Optional.of("TOEIC")))).thenReturn(true);
        assertEquals(List.of(), service.overrides(testId));
    }
}
