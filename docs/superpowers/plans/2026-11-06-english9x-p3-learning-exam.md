# P3 — Học & thi: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Học viên học theo khoá (Chương → Bài) với luật mở khoá, luyện tập chấm ngay, kiểm tra chương, và làm đề thi thử TOEIC/IELTS trong phòng thi theo điều kiện thi thật.

**Architecture:** `course` + `learning` dùng `UnlockPolicy`, `ChapterTestAttempts`, `Streaks`, `StudyTime` của `e9x-domain`; `exam` bọc `ExamAttempt` (domain) trong aggregate JPA có khoá lạc quan (`@Version`) và phát `AttemptSubmitted`. Phòng thi frontend dùng Zustand + IndexedDB.

**Tech Stack:** Spring Boot, JPA, PostgreSQL; React, Zustand, idb-keyval, MediaRecorder API.

**Spec:** §8, §9, §10.1. Global Constraints + Review Focus: overview.

---

### Task P3.1: khoá học, danh mục, luật mở khoá (W6) — F19, F20

**Files:** `backend/e9x-app/src/main/java/com/english9x/course/**`, migration `V202611130001__courses.sql` (mở rộng `courses` của L1d: `course_versions`, `chapters`, `lessons`, `unlock_rule`, `description`, `author_display`), `frontend/src/features/catalog/**`, `frontend/src/features/cms/course-builder/**`, E2E `F19-course-unlock-rules.spec.ts`, `F20-guest-catalog.spec.ts`.

**Interfaces:**
- `GET /api/catalog/courses?exam=` (công khai: PUBLISHED English9x + kho chung APPROVED); `GET /api/catalog/courses/{code}` (mô tả, mục lục chương/bài, tác giả — không có nội dung bài).
- CMS: `POST/PUT /api/cms/courses {examCode, code (6 ký tự), title, description, unlockRule, chapters: [{title, passThreshold, lessons: [{kind, refId, maxAttempts}]}]}`; Reviewable với phụ thuộc là mọi bài/đề.
- `course.api.CourseOutline#lessonsInOrder(courseVersionId)` → `List<ChapterRef>` (record của domain).
- `course.api.LessonGate#check(userId, lessonId, now)` → `ALLOWED | NOT_ENTITLED | LOCKED` (dùng `AccessPolicy` qua `billing.api.Entitlements` và `UnlockPolicy`).

**Tests:** `CourseServiceIT.codeMustBeSixUppercaseAlnum`, `LessonGateIT.strictLocksUntilChapterTestPassed`, `.completionIgnoresScore`, `.freeOpensAll`, `.notEntitledBeatsUnlock`, `CatalogIT.guestSeesOutlineNotContent`.

### Task P3.2: learning — bài học, luyện tập, kiểm tra chương, thời gian học (W7) — F22, F23 (+ dữ liệu cho F24)

**Files:** `backend/e9x-app/src/main/java/com/english9x/learning/**`, migration `V202611140001__learning.sql` (`lesson_progress`, `practice_attempts`, `chapter_test_attempts`, `study_time_daily`, `study_streaks`), `frontend/src/features/learning/{LessonPage,PracticeBlock,ChapterTestPage,ChapterMap}.tsx`, `frontend/src/features/learning/useStudyHeartbeat.ts`, E2E `F22-lesson-practice.spec.ts`, `F23-chapter-test.spec.ts`.

**Interfaces:**
- `POST /api/learning/lessons/{id}/practice {questionVersionId, answer}` → `{correct, earned, explanation, correctAnswer}` (luyện tập được xem đáp án ngay; dùng `ObjectiveScorers`).
- `POST /api/learning/lessons/{id}/complete`; `POST /api/learning/chapter-tests/{id}/attempts` (chặn khi `!ChapterTestAttempts.canRetake`); nộp → điểm, cập nhật `bestScore`.
- `POST /api/learning/heartbeat {courseId, lessonId}` mỗi `e9x.learning.heartbeat-interval`; server lưu tín hiệu ngày hiện tại và tính `StudyTime.credited(…, e9x.learning.idle-timeout)` vào `study_time_daily`; streak theo `Streaks.current(…, e9x.learning.streak.min-minutes)` với `ZoneId` của người dùng.
- Frontend chỉ gửi heartbeat khi `document.visibilityState === 'visible'` và có tương tác trong 2 phút.

**Tests:** `PracticeIT.returnsExplanationImmediately`, `ChapterTestIT.retakeLimitEnforced`, `.bestScoreKept`, `HeartbeatIT.idleGapNotCredited`, `.streakUsesUserZone` (người dùng ở Asia/Ho_Chi_Minh học 23:50 và 00:10 → hai ngày).

### Task P3.3: exam backend (W6) — F29, F30, F31 (+ API cho F25–F28, F32)

**Files:** `backend/e9x-app/src/main/java/com/english9x/exam/**`, migration `V202611130002__exam.sql` (mở rộng `exam_attempts` của L1d: `max_resumes`, `resume_count`, `interrupted`, `submit_reason`, `current_section_index`, `current_section_started_at`, `version`; `attempt_sections`, `responses (pk attempt_id, question_id)`, `audio_plays`, `speaking_recordings`), job handler `auto_submit_attempt`.

**Interfaces:**
- `POST /api/exam/attempts {testVersionId}` → 201 `{attemptId, sections, currentSection, endsAt, serverNow}`; 409 khi đã có lượt IN_PROGRESS (index L1d) → trả lượt đó.
- `POST /api/exam/attempts/{id}/resume` → gọi `ExamAttempt.resume(now)`; `AUTO_SUBMITTED` → 409 kèm kết quả nộp.
- `PUT /api/exam/attempts/{id}/responses/{questionId} {answer}` → 204; 409 nếu `!canAnswer(section, now)`. Upsert theo khoá chính.
- `POST /api/exam/attempts/{id}/sections/{sectionId}/finish`; `POST /api/exam/attempts/{id}/submit` (idempotent: lần 2 trả 200 cùng kết quả).
- `GET /api/exam/attempts/{id}/audio/{mediaId}` → URL ký một lần; lần 2 → 410 (ghi `audio_plays`).
- `POST /api/exam/attempts/{id}/recordings/{questionId}` (multipart webm/opus, chỉ trong cửa sổ thời gian part Speaking + 10 giây dung sai upload).
- Lên lịch job `auto_submit_attempt` tại deadline; job gọi `submit(now, TIME_UP)`.
- Mọi thay đổi trạng thái: tải aggregate, gọi domain với `now` từ `Clock` bean, lưu với `@Version`; xung đột → thử lại 1 lần.
- `AttemptSubmitted` phát sau commit (Spring Modulith event publication).

**Tests:** `ExamAttemptIT.secondStartReturnsExisting`, `.responseRejectedAfterSectionEnd`, `.resumeBeyondLimitSubmitsInterrupted`, `ConcurrentSubmitIT.learnerAndJobSubmitConcurrentlyYieldsOneResult` (Review Focus #2), `AudioOnceIT.secondFetchGone`, `ExamDtoIT.noAnswerKeyInAnyResponse`, `AttemptSubmittedEventIT.publishedOnce`.

### Task P3.4: phòng thi UI (W7) — F25, F26, F27, F28, F29, F32

**Files:** `frontend/src/features/exam/**` (`ExamRoom`, `SectionTimer` (đếm theo `endsAt` − lệch đồng hồ `serverNow`), `ReadingSplitView`, `ListeningPlayer` (không có điều khiển tua/dừng), `WritingEditor` (đếm từ, `spellcheck=false`), `SpeakingRecorder` (kiểm micro, đếm ngược chuẩn bị, ghi đúng thời lượng, upload + thử lại), `QuestionNavigator`, `SaveStatus`, `offlineQueue.ts` (IndexedDB)), renderer cho 12 `InteractionKind`, E2E F25–F29, F32.

**Interfaces:** tự lưu theo `e9x.exam.autosave-interval` (nhận từ `GET /api/config/public`) và khi đổi câu; mất mạng → xếp hàng IndexedDB, phát lại theo thứ tự khi có mạng; phím tắt A/B/C/D, ←/→, F (đánh dấu); cảnh báo đồng hồ ở 5 và 1 phút; màn hình nhỏ hơn 768 px ở IELTS Reading/Writing → banner khuyến nghị (không chặn).

**Tests:** Vitest `offlineQueue.replaysInOrder`, `SectionTimer.usesServerOffset`, `ListeningPlayer.hasNoSeekControls`, `SpeakingRecorder.stopsAtDuration` (MediaRecorder giả); E2E: F25 (TOEIC 200 câu — dùng `seed.publishedToeicTest()` và trả lời theo bảng đáp án seed để kiểm điểm), F26 (AC và GT), F27, F28 (`context.setOffline(true)`), F29 (tải lại 3 lần), F32 (Chromium `--use-fake-device-for-media-stream`).
