package com.english9x.org;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.english9x.content.media.MediaIT;
import com.english9x.content.media.api.StagedFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

/**
 * Personal-data erasure of a Speaking recording (spec §15): voice is biometric data, so pseudonymizing the account is not enough — the audio OBJECT in the store
 * must go too. This drives the real MinIO: a recording is staged and its row seeded, the SYS_ADMIN erases the learner, and the object is gone from storage and the
 * row from the database. PersonalDataService deletes the object before the row, so a store failure could not silently orphan the blob.
 */
class PersonalDataRecordingErasureIT extends MediaIT {

    @Autowired StagedFiles staged;

    @Test
    void erasingThePersonDeletesTheVoiceObjectFromStorage() throws Exception {
        UUID learner = user("rec-person@x.test", true);
        UUID attempt = UUID.randomUUID();
        UUID question = UUID.randomUUID();
        su.update("insert into exams(code,name) values ('IELTS','IELTS') on conflict do nothing");
        su.update("insert into exam_attempts(id,user_id,owner_org_id,test_version_id,status,exam_code,max_resumes,submitted_at,interrupted,submit_reason) "
                + "values (?,?,null,?,'SUBMITTED','IELTS',2, now(), false,'LEARNER')", attempt, learner, UUID.randomUUID());
        su.update("insert into attempt_questions(attempt_id,question_id,user_id,question_version_id,section_code) values (?,?,?,?,'SPEAKING')",
                attempt, question, learner, UUID.randomUUID());

        // stage a real object and point a recording row at it
        String key = "staging/recordings/" + attempt + "/" + question + ".webm";
        Path tmp = Files.createTempFile("rec", ".webm");
        Files.write(tmp, new byte[] {1, 2, 3, 4, 5});
        staged.put(key, tmp, "audio/webm");
        su.update("insert into speaking_recordings(attempt_id,question_id,user_id,storage_path,mime_type,size_bytes,uploaded_at) values (?,?,?,?,'audio/webm',5, now())",
                attempt, question, learner, key);

        // the object is really there before erasure
        Path got = Files.createTempFile("got", ".webm");
        staged.get(key, got);
        assertEquals(5, Files.size(got), "the voice object is in the store");

        // erase the learner
        assertEquals(204, call(HttpMethod.DELETE, "/api/admin/users/" + learner + "/personal-data", null, sysAdmin(), null).status());

        // the row and the object are both gone
        assertEquals(0, (int) su.queryForObject("select count(*) from speaking_recordings where user_id = ?", Integer.class, learner), "the recording row is gone");
        Path after = Files.createTempFile("after", ".webm");
        assertThrows(RuntimeException.class, () -> staged.get(key, after), "the voice object is gone from storage");
    }
}
