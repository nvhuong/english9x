# P4 — Chấm & phân tích: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Chấm tự động + quy đổi, chấm Writing/Speaking bằng Gemini và giáo viên theo chính sách, trang kết quả có điểm yếu theo dạng câu, tiến trình theo gói và giám sát AI.

**Architecture:** `grading` lắng nghe `AttemptSubmitted`, dùng `ObjectiveScorers`, `ScoreConverter`, `GradingPolicyResolver`, `OfficialScore`, `GradingQueueItem` của `e9x-domain`; Gemini sau interface `AiGrader`; `analytics` lắng nghe `AttemptScored`.

**Tech Stack:** Spring Boot, Java HttpClient (Gemini REST `generateContent`, `responseMimeType: application/json`, `responseSchema`), PostgreSQL; React, biểu đồ bằng visx.

**Spec:** §10, §8 (tiến trình), §15.1 (khoá `e9x.grading.*`). Global Constraints + Review Focus: overview. **Không có giới hạn lượt chấm AI** — `e9x.grading.ai.per-user-daily-limit=0`.

---

### Task P4.1: chấm tự động + quy đổi (W6) — F33, F25/F26 (điểm), F15 (áp dụng bảng)

> **Ghi chú 2026-11-07 (từ P1.11):** listener đầu tiên (`@ApplicationModuleListener` trên `AttemptSubmitted`) chính là thứ chứng minh quy tắc liên module (`exam::events`) qua `ModularityTest`; P1.11 chỉ kiểm tra phía phát hành. P4.1 phải nối persistence của `spring-modulith-events` (bảng `event_publication`, spec §6), vì giao ít nhất một lần mới chỉ được ghi trong `docs/superpowers/design/events.md`.

**Files:** `backend/e9x-app/src/main/java/com/english9x/grading/auto/**`, migration `V202611130003__scores.sql` (`scores`), E2E `F33-auto-scoring.spec.ts`.

**Interfaces:** `@ApplicationModuleListener on AttemptSubmitted` → đọc đáp án (role `e9x_grader`, đặt `app.org_id` của lượt thi; policy grader cho đọc câu PUBLISHED của English9x và câu của tổ chức đó — ruling review wave cục bộ). Raw là tổng điểm có thể lẻ (chấm một phần); `TableConverter` làm tròn xuống với dung sai 1e-9, chấm từng câu bằng `ObjectiveScorers.forKind`, cộng raw theo kỹ năng, quy đổi bằng `catalog.api.ScoreTables#resolve` (lưu `score_table_version_id`), TOEIC tổng `Toeic.total`, IELTS overall `Ielts.overall` khi đủ 4 kỹ năng; câu W/S chuyển sang P4.2/P4.3; phát `AttemptScored` khi mọi kỹ năng có điểm hiển thị.

**Tests:** `AutoGradingIT.toeicScoresWithTestOverrideTable`, `.ieltsListeningReadingBands`, `.interruptedAttemptScoredAndFlagged`, `.scoreTableVersionRecorded`, `.idempotentOnDuplicateEvent`.

### Task P4.2: chấm AI bằng Gemini (W7) — F34, F37

**Files:** `backend/e9x-app/src/main/java/com/english9x/grading/ai/**` (`AiGrader`, `GeminiAiGrader`, `AiGradeJobHandler`, `PromptRenderer`), migration `V202611140002__ai_evaluations.sql`, E2E `F34-ai-grading.spec.ts`, `F37-gemini-failure.spec.ts`; contract test `backend/e9x-app/src/test/java/com/english9x/grading/ai/GeminiContractIT.java` (chỉ chạy khi có `GEMINI_API_KEY`).

**Interfaces:**
- `interface AiGrader { AiResult grade(SkillVersion skill, Task task, Response response) }` — `Response` là text hoặc audio (bytes + mime).
- Cấu hình: `e9x.gemini.base-url`, `e9x.gemini.model`, `e9x.gemini.api-key` (env), timeout 60 s; retry theo `e9x.grading.ai.retry-delays`; sai schema thử lại `e9x.grading.ai.schema-retry` lần, kèm lỗi validate trong prompt.
- Hết lượt thử → trạng thái `AI_FAILED`, thông báo `SYS_ADMIN` (sự kiện "Chấm AI lỗi"); học viên thấy "Đang chấm".
- Công tắc khẩn cấp `e9x.grading.ai.per-user-daily-limit` (mặc định 0 = tắt): khi > 0 và vượt, bài chuyển hàng chờ giáo viên thay vì chấm AI — không từ chối học viên.
- Lưu `ai_evaluations` (skill version, model, input/output tokens, chi phí ước tính theo bảng giá cấu hình `e9x.gemini.price-per-1k-*`).
- Dry-run cho P2.5: `AiGrader` dùng chung, không lưu.

**Tests:** `GeminiAiGraderTest` (fake HTTP server) `.validJsonParsed`, `.schemaViolationRetriedOnceWithErrors`, `.serverErrorRetriedWithConfiguredDelays`, `AiGradeJobIT.exhaustedRetriesMarksAiFailedAndNotifiesAdmin`, `.dailyLimitZeroMeansUnlimited`, `.dailyLimitPositiveRoutesToTeacherQueue`.

### Task P4.3: hàng chờ giáo viên + chế độ Cả hai (W8) — F35, F36

**Files:** `backend/e9x-app/src/main/java/com/english9x/grading/teacher/**`, migration `V202611150001__grading_queue.sql` (`grading_queue_items`, `teacher_reviews`), job `release_expired_claim`, `frontend/src/features/teacher/{QueuePage,GradingPage}.tsx`, E2E `F35-teacher-queue.spec.ts`, `F36-both-mode.spec.ts`.

**Interfaces:** khi chính sách là `TEACHER`/`BOTH` → tạo mục hàng chờ theo (kỳ thi, bên chấm: tổ chức nếu trung tâm chọn GV của mình, ngược lại English9x); `GET /api/teacher/queue?exam=`; `POST /api/teacher/queue/{id}/claim` (dùng `GradingQueueItem.claim`, khoá lạc quan); `POST /api/teacher/queue/{id}/review {criteria: {code: score}, comment}` → điểm GV, `OfficialScore.select` quyết định hiển thị (điểm GV luôn là chính thức khi có, kể cả khi chế độ là AI và bài được chuyển cho GV vì AI lỗi); lưu độ lệch AI–GV; `POST /api/admin/queue/{id}/reassign {teacherId}`; job trả bài quá `e9x.grading.claim-timeout`.

**Tests:** `TeacherQueueIT.claimedHiddenFromOthers`, `.expiredClaimReturnsToQueue`, `.bothModeTeacherReplacesPreliminary`, `.deviationRecorded`, `.centerQueueOnlyVisibleToCenterTeachers`.

### Task P4.4: kết quả, điểm yếu, gợi ý (W9) — F38

**Files:** `backend/e9x-app/src/main/java/com/english9x/analytics/results/**`, migration `V202611160001__item_type_stats.sql`, `frontend/src/features/results/**`, E2E `F38-results-weakness.spec.ts`.

**Interfaces:** `GET /api/results/attempts/{id}` → điểm tổng/kỹ năng (nhãn PRELIMINARY/OFFICIAL), từng câu (đúng/sai, đáp án, giải thích, transcript), W/S theo tiêu chí + nhận xét, cờ gián đoạn; `GET /api/results/weakness?exam=` → tỉ lệ đúng theo `question_type` qua các lần thi; gợi ý = 3 dạng câu có tỉ lệ đúng thấp nhất có bài phương pháp trong khoá người dùng có entitlement.

**Tests:** `ResultsIT.answerKeyVisibleOnlyAfterSubmit`, `WeaknessIT.aggregatesAcrossAttempts`, `RecommendationIT.onlyEntitledLessons`.

### Task P4.5: tiến trình theo gói + giám sát AI (W9) — F24, F56

**Files:** `backend/e9x-app/src/main/java/com/english9x/analytics/progress/**`, `backend/e9x-app/src/main/java/com/english9x/analytics/aimonitor/**`, `frontend/src/features/progress/**`, `frontend/src/features/admin/AiMonitorPage.tsx`, E2E `F24-progress-by-plan.spec.ts`, `F56-ai-monitoring.spec.ts`.

**Interfaces:** `GET /api/me/progress` → thẻ theo gói (nguồn ORDER/SEAT, kích hoạt, hết hạn, ngày còn lại, khoá dùng/tối đa) → khoá (% hoàn thành, chương/bài xong, kiểm tra chương, đề đã thi, điểm gần nhất/tốt nhất, bài giao chưa nộp, lessonId "Học tiếp"), chuỗi thời gian điểm dự đoán, phút học theo tuần, streak; gói hết hạn ở mảng `expired` (chỉ đọc). `GET /api/orgs/{id}/learners/{userId}/progress` (ORG_ADMIN/TEACHER). `GET /api/admin/ai-monitor?from=&to=` → job lỗi, chi phí/ngày/người, độ lệch AI–GV theo dạng câu, cảnh báo khi chi phí ngày vượt `e9x.grading.ai.daily-cost-alert`.

**Tests:** `ProgressIT.groupsCoursesByPlanSource`, `.expiredPlanReadOnlyAndResumable` (mua lại khoá → tiến độ nối tiếp), `AiMonitorIT.costAlertRaisedAboveThreshold`.
