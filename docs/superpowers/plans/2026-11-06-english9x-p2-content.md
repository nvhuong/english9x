# P2 — Nội dung & CMS: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Người biên soạn/duyệt được giao theo kỳ thi soạn, import, duyệt và xuất bản câu hỏi, đề, bài phương pháp, bảng quy đổi và skill chấm cho IELTS AC/GT và TOEIC L&R.

**Architecture:** `catalog` giữ định nghĩa kỳ thi, 12 kiểu tương tác, 31 dạng câu (JSON Schema trong `backend/e9x-app/src/main/resources/catalog/question-types/*.schema.json`); `content` lưu phiên bản bất biến; mọi vòng đời đi qua `workflow.api.ReviewService` (P1.6). Form CMS sinh từ JSON Schema.

**Tech Stack:** Spring Boot, networknt json-schema-validator, Apache POI, MinIO Java SDK, Jaffree/ffprobe (đọc độ dài media); React, @rjsf/core (form từ JSON Schema), TipTap không dùng — trình soạn bài là trình soạn khối tự viết trên Radix.

**Spec:** §3, §7, §10.2, §10.3 (skill chấm). Global Constraints + Review Focus: overview.

---

### Task P2.1: catalog (W3) — nền F09, F15, F16

**Files:** `backend/e9x-app/src/main/java/com/english9x/catalog/**`, migration `V202611100001__catalog.sql` (`exams`, `exam_sections`, `interaction_kinds`, `question_types`, `score_tables`, `score_table_versions`, `grading_skills`, `grading_skill_versions`, `grading_policies`), seed `V202611100002__catalog_seed_ielts_toeic.sql`, `backend/e9x-app/src/main/resources/catalog/question-types/{TOEIC_P1_PHOTO,…,IELTS_S_PART3}.schema.json` (31 file).

**Interfaces (Produces):**
- `catalog.api.QuestionTypes#get(String code)` → `QuestionTypeDef(code, examCode, section, InteractionKind kind, JsonSchema contentSchema, JsonSchema answerKeySchema, Optional<String> excelTemplateId)`.
- `catalog.api.SchemaValidator#validate(String typeCode, JsonNode content, JsonNode answerKey)` → `List<FieldError(path, message)>`.
- `catalog.api.ScoreTables#resolve(examCode, skill, Optional<UUID> testVersionId)` → `ScoreConverter` (dùng `TableConverter`, `Toeic`, `Ielts` của `e9x-domain`) + `scoreTableVersionId`.
- `catalog.api.GradingPolicies#resolve(examCode, Optional<UUID> orgId, Optional<UUID> testVersionId)` → `GradingMode` (gọi `GradingPolicyResolver`).

**Tests:** `QuestionTypeSchemasTest.everySeededTypeHasValidSchemas` (31 dạng, mỗi dạng có 1 ví dụ hợp lệ và 1 ví dụ sai trong `src/test/resources/catalog/examples/`), `ScoreTablesIT.testOverrideBeatsExamDefault`, `.readingAcAndGtAreDistinct`, `GradingPoliciesIT.precedenceTestOrgExam`.

### Task P2.2: media (W3) — F12

**Files:** `backend/e9x-app/src/main/java/com/english9x/content/media/**`, migration `V202611100003__media.sql`, `frontend/src/features/cms/media/**`, E2E `e2e/specs/cms/F12-media.spec.ts`.

**Interfaces:** `POST /api/cms/media` (multipart; kiểm loại MIME theo nội dung: audio mp3/m4a/webm/ogg, image png/jpg/webp, video mp4/webm; giới hạn kích thước cấu hình) → `{id, kind, durationMs}`; `MediaUrls#signedUrl(UUID mediaId, Duration ttl)` dùng `e9x.media.url-ttl`; `GET /api/media/{id}/url` kiểm quyền (người biên soạn kỳ thi hoặc học viên có entitlement khoá chứa media).

**Tests:** `MediaUploadIT.rejectsSpoofedExtension`, `.readsAudioDuration`, `MediaAccessIT.learnerWithoutEntitlementDenied`, `.urlExpiresAfterTtl`.

### Task P2.3: câu hỏi + nhóm câu (W4) — F09, F18

**Files:** `backend/e9x-app/src/main/java/com/english9x/content/question/**`, migration `V202611110001__questions.sql` (mở rộng bảng `questions` của L1d: `type_code`, `group_id`, `version_no`, `explanation`, `points`; bảng `question_groups`, `question_versions`, trigger chặn UPDATE bản PUBLISHED), `frontend/src/features/cms/questions/**`, E2E `F09-question-forms.spec.ts`.

**Interfaces:** `POST /api/cms/questions {typeCode, groupId?, content, answerKey, explanation, points}` → 201 / 422 với `errors[]` theo JSON path; `PUT /api/cms/questions/{id}` tạo bản nháp mới nếu bản hiện tại đã xuất bản; `GET /api/cms/questions?exam=&type=&status=`. Implements `workflow.api.Reviewable`.

**Tests:** `QuestionServiceIT.invalidContentRejectedWithJsonPaths`, `.editingPublishedCreatesNewDraftVersion`, `.authorOfOtherExamForbidden`. E2E F09 tạo một câu cho mỗi dạng câu (31), F18 kiểm phiên bản.

> **Ghi chú 2026-11-08 (từ P1.6):** (1) Bảng version gắn `e9x_guard_version_lifecycle` (xem ghi chú P1.6 trong plan nền). Bảng con của một version cần guard riêng chặn INSERT, UPDATE, DELETE khi version cha không ở trạng thái sửa được (DRAFT/CHANGES_REQUESTED); test kỳ vọng: sửa/thêm/xoá dòng con của version PUBLISHED hoặc IN_REVIEW bị từ chối. (2) RLS bảng version phải cho REVIEWER của kỳ thi đọc và khoá (UPDATE policy) hàng version trong ngữ cảnh của nội dung. (3) Mọi AUTHOR của kỳ thi sửa được bản nháp của đồng nghiệp, nhưng `Reviewable.authorId()` chỉ là một người: P2 phải ghi lại người đóng góp hoặc hạn chế việc sửa bản của người khác (mặc định hạn chế sửa bản của đồng nghiệp trừ khi spec nói khác; chủ dự án quyết định) và đưa tập rộng hơn vào kiểm tra tự duyệt. (4) Không dùng cột stored generated trên bảng được bảo vệ.

### Task P2.4: bài phương pháp (W4) — F14

**Files:** `backend/e9x-app/src/main/java/com/english9x/content/strategy/**`, migration `V202611110002__strategy_lessons.sql`, `backend/e9x-app/src/main/resources/catalog/strategy-block.schema.json`, `frontend/src/features/cms/lesson-editor/**` (khối: heading, paragraph, table, image, audio, video-upload, video-embed (YouTube/Vimeo, kiểm domain), callout tip/trap, embedded-question, worked-example), `frontend/src/features/learning/StrategyLessonView.tsx`, E2E `F14-strategy-lesson.spec.ts`.

**Interfaces:** bài lưu `blocks: StrategyBlock[]` + `questionTypeCodes: string[]` (≥ 1); `embedded-question` tham chiếu `questionVersionId` đã PUBLISHED (phụ thuộc trong workflow).

**Tests:** `StrategyLessonIT.requiresAtLeastOneQuestionType`, `.embeddedQuestionMustBePublishedBeforeLessonPublishes`, `.rejectsNonAllowlistedVideoHost`; Vitest cho từng khối (render học viên ẩn đáp án câu nhúng cho tới khi làm).

### Task P2.5: bảng quy đổi + skill chấm (W4) — F15, F16

**Files:** `backend/e9x-app/src/main/java/com/english9x/catalog/scoring/**`, `backend/e9x-app/src/main/java/com/english9x/catalog/skill/**`, `frontend/src/features/cms/{score-tables,grading-skills}/**`, E2E `F15-score-tables.spec.ts`, `F16-grading-skill.spec.ts`.

**Interfaces:**
- Bảng: `POST/PUT /api/cms/score-tables {examCode, skill, scope: EXAM|TEST, testId?, rows: [{raw, scaled}]}` (validate bằng `TableConverter.of`).
- Skill: `POST/PUT /api/cms/grading-skills {questionTypeCode, rubric (markdown), criteria: [{code, name, min, max, step}], promptTemplate (Mustache, biến {{rubric}} {{samples}} {{task}} {{response}}), samples: [{band, text|mediaId, commentary}], resultSchema (JSON Schema)}`; `POST /api/cms/grading-skills/{id}/dry-run {response|mediaId}` → kết quả Gemini đã validate theo `resultSchema`, không lưu điểm (dùng `AiGrader` của P4.2 — trước khi P4.2 xong, dry-run trả 501 và test đánh dấu phụ thuộc).

**Tests:** `ScoreTableServiceIT.rejectsNonContiguousRows`, `.publishedTableImmutable`, `GradingSkillIT.promptRendersAllVariables`, `.resultSchemaMustDeclareEveryCriterion`.

### Task P2.6: dựng đề (W5) — F13

**Files:** `backend/e9x-app/src/main/java/com/english9x/content/test/**`, migration `V202611120001__tests.sql` (`tests`, `test_versions`, `test_parts`, `test_part_groups`), `frontend/src/features/cms/test-builder/**`, E2E `F13-test-builder.spec.ts`.

**Interfaces:** `POST /api/cms/tests {examCode, variant (AC|GT|null), title}`; `PUT /api/cms/tests/{id}/structure {parts: [{sectionCode, durationSec, groups: [groupVersionId]}], maxResumes, scoreTableOverrides[]}`; kiểm cấu trúc theo `exam_sections` (TOEIC: 7 part đúng số câu 6/25/39/30/30/16/54; IELTS: 4 kỹ năng, 40 câu L, 40 câu R, 2 task W, 3 part S) — cảnh báo khi lệch, chặn xuất bản khi thiếu kỹ năng. `TestVersionView#forExamRoom(versionId)` trả DTO **không có** `answer_key` (Review Focus Global Constraint).

**Tests:** `TestStructureValidatorTest.toeicRequiresSevenParts`, `.ieltsRequiresFourSkills`, `TestVersionViewIT.examRoomDtoHasNoAnswerKey` (serialize JSON và tìm chuỗi `answer`), `.publishBlockedUntilAllGroupsPublished`.

> **Ghi chú 2026-11-08 (từ P1.6):** các bảng con của version đề (`test_parts`, `test_part_groups`) cần guard riêng chặn INSERT, UPDATE, DELETE khi version đề cha không sửa được; test kỳ vọng: thêm/sửa/xoá phần hoặc nhóm của đề PUBLISHED/IN_REVIEW bị từ chối. Tương tự `chapters` và `lessons` ở các task khoá/bài (P2.4 và task dựng khoá) khi nội dung của chúng có version.

### Task P2.7: import Excel (W5) — F11

**Files:** `backend/e9x-app/src/main/java/com/english9x/content/importer/**` (`TemplateGenerator`, `RowParser`, `ImportJobHandler`), migration `V202611120002__import_jobs.sql`, `frontend/src/features/cms/import/**`, E2E `F11-excel-import.spec.ts`, fixtures `e2e/fixtures/excel/{toeic_p5_ok.xlsx, toeic_p5_errors.xlsx, ielts_r_headings_ok.xlsx, media.zip}`.

**Interfaces:** `GET /api/cms/import/templates/{typeCode}.xlsx` (sinh từ JSON Schema: sheet `Huong_dan`, sheet `Du_lieu` có data validation cho enum, 1 dòng ví dụ); `POST /api/cms/import {typeCode, file, mediaZip?}` → `{jobId}`; job `excel_import`: validate mọi dòng (schema + media tồn tại trong zip + giới hạn `e9x.content.import.max-rows`, `max-media-size`), lỗi → `import_errors(row, column, message)` + file Excel tô đỏ tải được, **không tạo gì**; thành công → tạo toàn bộ DRAFT + `review_bundle`.

**Tests:** `TemplateGeneratorTest.templateColumnsMatchSchema` (mỗi dạng câu), `RowParserTest.reportsRowAndColumn` ("dòng 37: thiếu đáp án"), `ImportJobIT.anyErrorCreatesNothing`, `.successCreatesDraftVersionsInOneJob`, `.missingMediaFileReported`, `.overRowLimitRejected`.

> **Ghi chú 2026-11-07 (từ P1.7, vòng sửa 1):** `{jobId}` trả cho client là `import_jobs.id` (bảng của P2.7), không phải `jobs.id`: role `e9x_app` không đọc được `jobs`. Handler `excel_import` cập nhật `import_jobs` (tiến độ/lỗi) và dùng `JobExecutor#onExhausted` để đánh dấu import thất bại khi hết thử lại.

> **Ghi chú 2026-11-08 (từ P1.6, quyết định của controller):** lô import gom các bản nháp qua `import_jobs`; dòng `review_bundles` chỉ được tạo khi tác giả gọi `ReviewService.submit(BundleRef)` (spec §7.1 chỉ nói có thể gửi duyệt cả cụm). Bản nháp không nằm trong `review_bundles` (mọi phần tử của cụm chuyển sang IN_REVIEW ngay khi gửi). `e9x.workflow.max-bundle-size` (mặc định 2000, 1-5000) phải không nhỏ hơn `e9x.content.import.max-rows`; một dòng import có thể tạo nhiều version nên tính theo số version.

### Task P2.8: CMS UI (W5) — F06 (phần UI), F09, F10

**Files:** `frontend/src/features/cms/{workqueue,question-bank,question-form,preview}/**`, `frontend/src/routes/cms/*.routes.ts`, E2E `F10-preview-as-learner.spec.ts` (+ phần UI của F06, F09).

**Interfaces:** "Hàng việc của tôi" (nháp, bị yêu cầu sửa, chờ tôi duyệt) theo kỳ thi được giao; form sinh từ schema bằng @rjsf với widget riêng cho media, bảng, đáp án nhiều biến thể; "Xem trước như học viên" dùng component phòng thi của P3.4 (trước P3.4: dùng renderer tối thiểu theo `InteractionKind`, P3.4 thay thế; E2E F10 chạy lại sau P3.4).

**Tests:** Vitest `QuestionForm.rendersEveryInteractionKind`, `Preview.neverRendersAnswerKey`; E2E F10.

### Task P2.9: UI duyệt + so sánh phiên bản (W6) — F17, F18

**Files:** `frontend/src/features/cms/review/**` (danh sách chờ duyệt, xem cả cụm, diff JSON → hiển thị khác biệt trường theo trường, nút Yêu cầu sửa/Duyệt), E2E `F17-review-workflow.spec.ts`, `F18-versioning.spec.ts`.

**Tests:** Vitest `VersionDiff.highlightsChangedFields`; E2E F17 (gồm "người duyệt không duyệt được bản mình soạn" và "đề chưa đủ câu xuất bản thì không xuất bản được"), F18 (học viên đang thi vẫn thấy bản cũ).

> **Ghi chú 2026-11-08 (từ P1.6, quyết định của controller):** P2.9 sở hữu controller backend của luồng duyệt (`backend/e9x-app/src/main/java/com/english9x/content/review/**` hoặc gói tương đương): danh sách chờ duyệt (`ReviewService.open`), xem cụm (`members`), duyệt, yêu cầu sửa, duyệt/yêu cầu sửa cả cụm. Thêm endpoint thì phải đưa vào sweep (`SweepEndpoints`; hàng của org B đã có trong `SweepFixtures`) và làm mới snapshot OpenAPI. Ánh xạ lỗi đã có sẵn (ngoại lệ workflow là `ErrorResponseException`).
