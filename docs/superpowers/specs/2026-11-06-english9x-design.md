# English9x — Spec thiết kế bản phát hành v1

- Ngày: 2026-11-06
- Trạng thái: Chờ chủ dự án duyệt bản viết
- Repo: https://github.com/nvhuong/english9x
- Quy trình: Superpowers (brainstorming → spec → writing-plans → subagent-driven development + git worktrees → code review → E2E → PR). Không merge vào `main`.

---

## 1. Mục tiêu và phạm vi

English9x là nền tảng dạy và luyện thi tiếng Anh online, cho cả học viên tự học (B2C) và tổ chức (B2B: trung tâm/trường, doanh nghiệp). Cốt lõi sản phẩm: với **mỗi phần của mỗi kỳ thi**, hệ thống có **phương pháp giải** đúng format chính thức hiện hành, bài luyện đúng dạng, đề thi thử theo điều kiện thi thật, và chấm điểm (tự động, AI Gemini, giáo viên).

**Bản v1 bao gồm**
- Kỳ thi: **IELTS Academic, IELTS General Training, TOEIC Listening & Reading**.
- Đầy đủ B2C và B2B: CMS có duyệt theo kỳ thi, khoá học tuần tự, phòng thi, chấm AI + giáo viên, gói linh hoạt + VietQR xác nhận thủ công, tổ chức, gói suất, lớp, giao bài, lịch, điểm danh, báo cáo, thông báo app/email/Telegram.
- Engine chung để sau v1 thêm TOEFL iBT (format 2026), Cambridge (A2 Key → C2 Proficiency), PTE Academic (format 08/2025), VSTEP 3–5, Aptis ESOL, TOEIC Speaking & Writing **bằng dữ liệu cấu hình** (dạng câu, JSON Schema, rubric, skill chấm, bảng quy đổi), không đổi kiến trúc.

**Thành công của v1:** ra mắt kiểm chứng với vài trăm học viên, dưới 50 người thi đồng thời; học viên trả tiền qua QR và đi trọn luồng học → thi → chấm; ít nhất một trung tâm vận hành lớp trên hệ thống.

**Ngoài phạm vi v1:** cá nhân hoá lộ trình bằng bài kiểm tra đầu vào; giám khảo Speaking AI hội thoại thời gian thực; nhập đề từ Word/PDF bằng AI; cổng thanh toán tự động; chia doanh thu với trung tâm; phòng học video tích hợp; white-label; sao chép khoá English9x để sửa; Zalo.

**Nguyên tắc nội dung:** đề và câu hỏi là nội dung tự soạn theo format, không sao chép đề có bản quyền (ETS, IDP/British Council, Cambridge, Pearson). Mọi điểm hiển thị là **điểm dự đoán**.

---

## 2. Các quyết định đã chốt

| # | Chủ đề | Quyết định |
|---|---|---|
| 1 | Khách hàng | B2C và B2B; v1 làm cả hai cùng lúc |
| 2 | Cốt lõi | Mỗi phần thi của mỗi kỳ thi có phương pháp giải theo format chính thức hiện hành |
| 3 | Nguồn nội dung | Người có quyền nhập liệu trong CMS |
| 4 | Quyền | Theo bộ ba (tổ chức, kỳ thi, vai trò); luồng Nháp → Chờ duyệt → Yêu cầu sửa / Xuất bản |
| 5 | Chấm W/S | Giáo viên và AI (Gemini); skill chấm (prompt, rubric, bài mẫu) soạn trong CMS |
| 6 | Chế độ chấm | Quản trị đặt AI / Giáo viên / Cả hai; học viên không chọn |
| 7 | "Cả hai" | Điểm AI hiện ngay là sơ bộ; điểm giáo viên thay thế, là chính thức; GV thấy kết quả AI |
| 8 | Gói B2C | Linh hoạt: số tháng, số khoá tối đa, giá; học viên tự chọn khoá |
| 9 | Khoá học | Nhóm nội dung do người biên soạn đóng gói, có duyệt, thuộc một kỳ thi |
| 10 | Thanh toán | VietQR tĩnh, quản trị xác nhận thủ công; nội dung chuyển khoản có mã khoá |
| 11 | Thứ tự | Làm trọn vẹn IELTS (AC + GT) và TOEIC L&R trước |
| 12 | Quy mô | Vài trăm học viên, < 50 người thi đồng thời |
| 13 | Học trong khoá | Lộ trình tuần tự Chương → Bài; mỗi bài: phương pháp, ví dụ giải, luyện tập chấm ngay; kiểm tra cuối chương; tiến độ % |
| 14 | Mở khoá | Người biên soạn chọn cho từng khoá: khoá chặt theo điểm / theo hoàn thành / tự do |
| 15 | Đề thi thử | Nằm trong khoá học, chiếm suất khoá |
| 16 | Chưa mua | Chỉ xem giới thiệu khoá (mô tả, mục lục, người biên soạn) |
| 17 | Bài phương pháp | Văn bản định dạng, bảng, ảnh, audio, video, câu hỏi nhúng |
| 18 | Nhập liệu | Form theo dạng câu + import Excel hàng loạt có kiểm tra theo dòng |
| 19 | Speaking | Ghi âm theo câu hỏi audio thu sẵn, đúng thời gian chuẩn bị/nói |
| 20 | Phân bài GV | Hàng chờ chung theo kỳ thi; GV tự nhận; quá hạn tự trả về |
| 21 | Đăng nhập | Email + mật khẩu và Google |
| 22 | Thông báo | Trong app, email, Telegram; mỗi người bật/tắt theo sự kiện × kênh |
| 23 | Hạ tầng | Một VPS, Docker Compose (app, PostgreSQL, MinIO, sao lưu hằng ngày) |
| 24 | Quy đổi điểm | Bảng mặc định theo kỳ thi + ghi đè theo đề; có duyệt, có phiên bản |
| 25 | Điều kiện thi | Luôn như thi thật: tính giờ từng phần, audio phát một lần, không xem đáp án trước khi nộp, hết giờ tự nộp |
| 26 | Mất kết nối | Đồng hồ chạy theo server; được quay lại tối đa N lần, quá thì tự nộp, đánh dấu gián đoạn |
| 27 | Sau khi nộp | Điểm + chữa bài đầy đủ + phân tích điểm yếu theo dạng câu + gợi ý bài ôn |
| 28 | Giao diện | Hướng UI-UX Pro Max, mới lạ; không cần hỏi chủ dự án |
| B1 | Loại tổ chức | Trung tâm/trường (A) và doanh nghiệp (C) trên một nền tảng chung |
| B2 | Mua B2B | Gói theo suất; kích hoạt bằng QR thủ công hoặc nhập tay theo hợp đồng |
| B3 | Chấm B2B | Trung tâm tự chọn giáo viên của mình hoặc giáo viên English9x |
| B4 | Nội dung trung tâm | Trung tâm tự quản; muốn đưa lên kho chung thì English9x duyệt |
| B5 | Doanh thu | Không chia doanh thu trong hệ thống; khoá chia sẻ coi như khoá English9x |
| B6 | Lớp | Lớp/nhóm, giao bài có hạn, báo cáo + Excel, lịch học, điểm danh |
| B7 | Buổi online | Link họp ngoài; giáo viên điểm danh tay |
| B8 | Tài khoản | Một tài khoản dùng chung; mời email, import Excel, mã/link tham gia |
| B9 | Thời điểm B2B | Có trong v1 |
| K1 | Kiến trúc | Modular monolith (Spring Modulith) |
| K2 | Tách dữ liệu | Chung database, `owner_org_id`, lọc ở ứng dụng + PostgreSQL Row-Level Security |
| T1 | Tiến trình | Màn hình tiến trình theo gói, thời gian học theo tuần, chuỗi ngày học |
| T2 | Kiểm thử | Mọi tính năng có E2E (mục 16) |

**Giả định đã nêu, chủ dự án không phản đối** (sửa được khi duyệt spec)
- Giao diện song ngữ Việt/Anh; học và luyện dùng tốt trên điện thoại.
- Bài kiểm tra chương: người biên soạn đặt số lần làm lại tối đa (mặc định không giới hạn), tính điểm cao nhất.
- Gói hết hạn: tiến độ và kết quả giữ lại ở chế độ chỉ đọc; mua lại khoá thì nối tiếp tiến độ.
- Video: hỗ trợ cả upload (lưu riêng tư, link có hạn) và nhúng YouTube/Vimeo.
- Thời hạn nhận bài của giáo viên: mặc định 24 giờ, quản trị cấu hình; không hiển thị cam kết thời gian cho học viên.
- N lần quay lại: đặt theo đề, mặc định 2; bài gián đoạn vẫn được chấm, có nhãn cảnh báo.
- Trung tâm được đặt lại chế độ chấm cho học viên của mình.
- Không sao chép khoá English9x để sửa trong v1.
- Học viên rời tổ chức: tổ chức giữ lịch sử học trong thời gian đó (chỉ đọc); suất được thu hồi.
- Chuỗi ngày học: một ngày được tính khi học ≥ 10 phút hoặc hoàn thành ≥ 1 bài.

---

## 3. Kỳ thi trong v1

### 3.1 Khái niệm
- **Kiểu tương tác** (`interaction_kind`, khoảng 12 loại, dùng chung mọi kỳ thi): `MCQ_SINGLE`, `MCQ_MULTI`, `GAP_TYPED`, `GAP_SELECT`, `MATCHING`, `TRUE_FALSE_NG`, `YES_NO_NG`, `LABEL_DIAGRAM`, `SHORT_ANSWER`, `ORDERING`, `ESSAY`, `SPEAKING_RESPONSE`. Mỗi kiểu có component hiển thị và bộ chấm riêng.
- **Dạng câu** (`question_type`, riêng từng kỳ thi; về sau khoảng 150): ví dụ `TOEIC_P2_QUESTION_RESPONSE`, `IELTS_R_MATCHING_HEADINGS`. Mỗi dạng câu trỏ tới một kiểu tương tác, có JSON Schema nội dung/đáp án, mẫu Excel, và là khoá liên kết với bài phương pháp giải, thống kê điểm yếu, skill chấm.

### 3.2 TOEIC Listening & Reading
- Listening ~45 phút, 100 câu: Part 1 Photographs (6), Part 2 Question-Response (25), Part 3 Conversations (39), Part 4 Talks (30).
- Reading 75 phút, 100 câu: Part 5 Incomplete Sentences (30), Part 6 Text Completion (16), Part 7 Single Passages (29), Part 7 Multiple Passages (25).
- Điểm: Listening 5–495, Reading 5–495, tổng 10–990; quy đổi từ số câu đúng bằng bảng (mục 10.2).
- Dạng câu v1: `TOEIC_P1_PHOTO`, `TOEIC_P2_QUESTION_RESPONSE`, `TOEIC_P3_CONVERSATION` (có biến thể kèm hình), `TOEIC_P4_TALK` (có biến thể kèm hình), `TOEIC_P5_INCOMPLETE_SENTENCE`, `TOEIC_P6_TEXT_COMPLETION` (gồm chèn câu), `TOEIC_P7_SINGLE_PASSAGE`, `TOEIC_P7_MULTIPLE_PASSAGES`.

### 3.3 IELTS (Academic và General Training)
- Listening ~30 phút, 4 phần, 40 câu; 6 dạng: multiple choice, matching, plan/map/diagram labelling, form/note/table/flow-chart/summary completion, sentence completion, short answer.
- Reading 60 phút, 40 câu, 3 bài (AC: văn bản học thuật; GT: xã hội, công việc, chung); 11 dạng: multiple choice, identifying information (TFNG), identifying writer's views (YNNG), matching information, matching headings, matching features, matching sentence endings, sentence completion, summary/note/table/flow-chart completion, diagram label completion, short answer.
- Writing 60 phút: Task 1 (AC: mô tả biểu đồ/quy trình ≥ 150 từ; GT: viết thư ≥ 150 từ), Task 2 (luận ≥ 250 từ, trọng số lớn hơn Task 1).
- Speaking 11–14 phút: Part 1 phỏng vấn (4–5 phút), Part 2 bài nói dài (1 phút chuẩn bị, 1–2 phút nói), Part 3 thảo luận (4–5 phút).
- Điểm: band 0–9 bước 0.5 cho từng kỹ năng. Overall = trung bình 4 kỹ năng, làm tròn theo quy tắc IELTS (.25 lên .5, .75 lên số nguyên kế tiếp). Writing band = (T1 + 2 × T2) / 3, làm tròn xuống 0.5 gần nhất. Tiêu chí Writing: Task Achievement/Response, Coherence & Cohesion, Lexical Resource, Grammatical Range & Accuracy. Tiêu chí Speaking: Fluency & Coherence, Lexical Resource, Grammatical Range & Accuracy, Pronunciation.
- Bảng quy đổi Reading AC và Reading GT là hai bảng riêng.
- Dạng câu v1: 6 dạng Listening + 11 dạng Reading (`IELTS_L_*`, `IELTS_R_*`), `IELTS_W_AC_TASK1`, `IELTS_W_GT_TASK1`, `IELTS_W_TASK2`, `IELTS_S_PART1`, `IELTS_S_PART2`, `IELTS_S_PART3`.

### 3.4 Kỳ thi sau v1 (để kiểm tra engine đủ tổng quát)
TOEFL iBT từ 21/01/2026 (Reading/Listening thích ứng nhiều chặng, điểm 1–6), PTE Academic (22 dạng, thêm 2 dạng từ 07/08/2025, câu tính cho nhiều kỹ năng), Cambridge 5 cấp độ, VSTEP 3–5 (một đề cho 3 bậc), Aptis (mỗi part nhắm bậc CEFR khác nhau), TOEIC S&W. Engine v1 phải hỗ trợ sẵn: điểm một câu cộng vào nhiều kỹ năng; quy đổi điểm qua hàm cấu hình (bảng, trung bình có trọng số, ngưỡng bậc); chặng thích ứng là điểm mở rộng của `exam` (không làm trong v1).

---

## 4. Vai trò và phân quyền

- Vai trò: `LEARNER`, `AUTHOR`, `REVIEWER`, `TEACHER`, `ORG_ADMIN`, `SYS_ADMIN`.
- `role_grants(user, org, exam, role)`: `org = NULL` là English9x; `exam = NULL` là mọi kỳ thi (chỉ dùng cho `ORG_ADMIN`, `SYS_ADMIN`).
- Người duyệt không được duyệt phiên bản do chính mình tạo.
- Người dùng chọn **ngữ cảnh** ("Cá nhân" hoặc một tổ chức) ở thanh trên cùng; mọi API nhận ngữ cảnh qua header `X-Org-Context` và được kiểm tra đúng thành viên.
- Doanh nghiệp (`ENTERPRISE`) không có `AUTHOR`, `REVIEWER`, `TEACHER`.
- Tài khoản nội bộ được mời qua email; học viên tự đăng ký.

---

## 5. Kiến trúc

### 5.1 Triển khai
VPS chạy Docker Compose: `app` (Spring Boot, phục vụ API và bản build React), `postgres` (16), `minio`, `caddy` (HTTPS), `backup` (pg_dump + mirror MinIO hằng ngày, giữ 14 bản). Dịch vụ ngoài: Gemini API, SMTP, Telegram Bot API. Môi trường dev/E2E thêm `mailpit` (bắt email), `fake-telegram`, `fake-gemini`.

### 5.2 Backend
Java 21, Spring Boot 3, Spring Modulith, Spring Security (JWT ngắn hạn + refresh token xoay vòng; OAuth2 Google), Spring Data JPA, Flyway, springdoc-openapi, Apache POI (Excel), ZXing (ảnh QR). Mỗi module có gói `api` công khai và `internal`; module chỉ gọi nhau qua `api` hoặc domain event; test kiến trúc chặn truy cập chéo.

| Module | Trách nhiệm | Dùng |
|---|---|---|
| `identity` | Tài khoản, email/Google, JWT, mời, mã tham gia | — |
| `org` | Tổ chức, thành viên, `role_grants`, ngữ cảnh, đặt biến RLS cho mỗi transaction | identity |
| `catalog` | Kỳ thi, phần thi, kiểu tương tác, dạng câu (JSON Schema), bảng quy đổi, skill chấm, chính sách chấm | org |
| `content` | Câu hỏi, media, import Excel, đề, bài phương pháp | catalog, org |
| `course` | Khoá, chương, bài, luật mở khoá, kho chung | content |
| `workflow` | Vòng đời duyệt và phiên bản dùng chung | org |
| `learning` | Tiến độ, bài luyện, kiểm tra chương, thời gian học, chuỗi ngày | course |
| `exam` | Phòng thi | content, identity |
| `grading` | Chấm tự động, AI, hàng chờ giáo viên | exam, catalog |
| `analytics` | Kết quả, điểm yếu theo dạng câu, gợi ý, tiến trình theo gói, báo cáo | grading, learning, billing |
| `classroom` | Lớp/nhóm, giao bài, lịch, điểm danh | org, course |
| `billing` | Gói, đơn VietQR, gói suất, gán suất, quyền học (`entitlements`) | org, course |
| `notification` | Thông báo app/email/Telegram, tuỳ chọn | identity |

### 5.3 Việc chạy nền
Bảng `jobs` trong PostgreSQL (lấy việc bằng `SELECT … FOR UPDATE SKIP LOCKED`), có số lần thử, thời gian chờ tăng dần, lỗi cuối cùng. Loại việc: `ai_grade`, `excel_import`, `send_notification`, `auto_submit_attempt`, `release_expired_claim`, `expire_orders`, `expire_entitlements`, `plan_expiry_reminder`. Ứng dụng có cờ `app.worker.enabled` để sau này tách một container chỉ chạy việc nền.

### 5.4 Frontend
React 18 + TypeScript, Vite, React Router, TanStack Query, Zustand (trạng thái phòng thi), Tailwind CSS + Radix UI, react-hook-form + zod, form sinh từ JSON Schema, i18next. Client API sinh từ OpenAPI. Bốn khu theo vai trò: Học viên, CMS, Tổ chức, Quản trị hệ thống.

---

## 6. Mô hình dữ liệu

**Nguyên tắc**
1. Bảng dữ liệu riêng có `owner_org_id` (NULL = English9x). RLS cho phép đọc: dữ liệu của tổ chức trong ngữ cảnh hiện tại + nội dung English9x đã xuất bản + khoá kho chung đã duyệt. Ứng dụng đặt `SET LOCAL app.org_id`, `app.user_id` cho mỗi transaction. Tài khoản DB của ứng dụng không phải chủ bảng, nên RLS luôn áp dụng.
2. Nội dung có phiên bản bất biến (`*_versions`); bản đã xuất bản không sửa; bài làm và kết quả trỏ tới phiên bản đã dùng.
3. Khung đề là quan hệ; nội dung và đáp án từng câu là JSONB, được kiểm tra bằng JSON Schema của dạng câu.
4. `answer_key` không bao giờ nằm trong DTO gửi cho phòng thi trước khi nộp.
5. Migration Flyway đặt tên `V<yyyymmddhhmm>__<module>_<mô tả>.sql`.

| Nhóm | Bảng |
|---|---|
| Tài khoản & tổ chức | `users`, `auth_identities`, `refresh_tokens`, `organizations` (type, cấu hình chấm, bên chấm), `org_memberships`, `role_grants`, `invitations`, `join_codes` |
| Danh mục | `exams`, `exam_sections`, `interaction_kinds`, `question_types`, `score_tables` + `score_table_versions`, `grading_skills` + `grading_skill_versions`, `grading_policies` (phạm vi: kỳ thi / tổ chức / đề) |
| Nội dung | `media_assets`, `question_groups` + versions, `questions` + versions, `tests` + `test_versions` (N lần quay lại, bảng quy đổi ghi đè), `test_parts`, `test_part_groups`, `strategy_lessons` + versions (khối nội dung JSON, gắn dạng câu), `import_jobs`, `import_errors` |
| Duyệt | `review_requests` (đối tượng, phiên bản, trạng thái, người gửi, người duyệt, nhận xét), `review_bundles` |
| Khoá học | `courses` + `course_versions` (luật mở khoá, mã khoá 6 ký tự, trạng thái kho chung), `chapters`, `lessons` (loại: bài học / kiểm tra chương / đề thi thử; ngưỡng; số lần làm lại) |
| Học tập | `lesson_progress`, `practice_attempts`, `chapter_test_attempts`, `study_time_daily`, `study_streaks` |
| Thi | `exam_attempts` (phiên bản đề, trạng thái, `deadline_at`, `resume_count`, `interrupted`), `attempt_sections`, `responses` (khoá chính attempt + question), `audio_plays`, `speaking_recordings` |
| Chấm | `scores` (loại: sơ bộ/chính thức, theo kỹ năng, phiên bản bảng quy đổi), `ai_evaluations` (phiên bản skill, model, JSON kết quả, token, chi phí), `teacher_reviews`, `grading_queue_items` (kỳ thi, bên chấm, trạng thái, người nhận, hạn) |
| Phân tích | `item_type_stats` |
| Thanh toán & quyền | `plans`, `orders`, `order_items`, `payment_accounts`, `org_licenses`, `org_license_courses`, `seat_assignments`, `entitlements` (người, khoá, từ, đến, nguồn: `order_id` hoặc `seat_assignment_id`) |
| Lớp học | `classes`, `class_members`, `assignments`, `assignment_submissions`, `class_sessions`, `attendance` |
| Thông báo | `notifications`, `notification_prefs`, `telegram_links` |
| Hệ thống | `jobs`, `event_publication`, `audit_log` |

**Quy tắc quyền học duy nhất:** người dùng mở được nội dung khi có `entitlement` còn hạn cho khoá chứa nội dung đó (người biên soạn/duyệt/giáo viên xem được nội dung thuộc kỳ thi được giao, không cần entitlement).

---

## 7. Nội dung, duyệt và import

### 7.1 Vòng đời
`DRAFT` → gửi duyệt → `IN_REVIEW` → `CHANGES_REQUESTED` (quay về sửa) hoặc `PUBLISHED` → `ARCHIVED`. Áp dụng cho câu hỏi, nhóm câu, đề, bài phương pháp, khoá, bảng quy đổi, skill chấm.
- Sửa bản đã xuất bản tạo bản nháp mới; học viên dùng bản cũ đến khi bản mới được xuất bản.
- **Phụ thuộc:** đề chỉ xuất bản khi mọi câu/nhóm đã xuất bản; khoá chỉ xuất bản khi mọi bài/đề đã xuất bản. Có thể gửi duyệt cả cụm (`review_bundle`).
- **Kho chung:** khoá đã xuất bản của trung tâm gửi sang hàng duyệt của English9x; được duyệt thì hiện trong danh mục chung, ghi trung tâm tác giả.

### 7.2 Trình soạn bài phương pháp
Bài gồm các khối JSON: `heading`, `paragraph`, `table`, `image`, `audio`, `video` (upload hoặc YouTube/Vimeo), `callout` (mẹo/bẫy), `embedded_question` (tham chiếu câu đã xuất bản), `worked_example` (các bước giải). Mỗi bài gắn ≥ 1 dạng câu.

### 7.3 Form câu hỏi
Sinh từ JSON Schema của dạng câu; nút "Xem trước như học viên" dùng đúng component phòng thi.

### 7.4 Import Excel
1. Tải mẫu `.xlsx` của dạng câu, sinh từ JSON Schema (sheet hướng dẫn, cột có danh sách chọn, một dòng ví dụ).
2. Upload `.xlsx` + `.zip` media (tên file khớp cột `media_file`).
3. Job `excel_import` kiểm tra mọi dòng. Có lỗi: từ chối cả lô, trả danh sách lỗi theo dòng và file Excel tô đỏ dòng lỗi. Không lỗi: tạo toàn bộ ở `DRAFT`, gom thành một lô gửi duyệt.
4. Giới hạn: 2.000 dòng và 500 MB media mỗi lô.

### 7.5 Media
Lưu riêng tư trên MinIO; phát qua presigned URL có hạn (mặc định 10 phút) cho người có quyền; tự đọc độ dài audio/video. Audio Listening trong phòng thi: mỗi đoạn chỉ cấp URL một lần cho mỗi lượt thi (`audio_plays`).

---

## 8. Học tập và tiến trình

- Khoá → Chương → Bài. Loại bài: bài học (phương pháp + ví dụ + luyện tập chấm ngay, xem đáp án và giải thích ngay), kiểm tra chương, đề thi thử.
- **Luật mở khoá** theo khoá: `STRICT` (xong bài trước; đạt ngưỡng kiểm tra chương mới mở chương sau), `COMPLETION` (chỉ cần hoàn thành/nộp), `FREE`.
- Kiểm tra chương: số lần làm lại theo cấu hình, tính điểm cao nhất.
- **Tiến trình học tập của tôi:** mỗi gói (cá nhân hoặc suất tổ chức) là một thẻ: nguồn, ngày kích hoạt/hết hạn, số ngày còn lại, số khoá đã dùng/tối đa; từng khoá: % hoàn thành, chương/bài đã xong, kiểm tra chương, đề đã thi và điểm dự đoán gần nhất/tốt nhất, bài được giao chưa nộp, nút "Học tiếp"; biểu đồ điểm dự đoán theo thời gian và tỉ lệ đúng theo dạng câu; gói hết hạn ở mục riêng, chỉ đọc.
- **Thời gian học:** trình duyệt gửi heartbeat mỗi 30 giây khi trang học/thi đang hiển thị và có tương tác trong 2 phút gần nhất; server cộng vào `study_time_daily`. Chuỗi ngày học tính theo múi giờ của người dùng.
- Quản trị tổ chức và giáo viên xem được góc nhìn này cho học viên trong tổ chức.

---

## 9. Phòng thi

- **Bắt đầu:** tạo `exam_attempt`, chốt phiên bản đề; mỗi phần có `starts_at`/`ends_at` do server tính; tối đa một lượt `IN_PROGRESS` mỗi người mỗi đề (partial unique index).
- **Lưu đáp án:** gửi khi đổi câu và mỗi 5 giây; upsert theo câu; khi mất mạng giữ trong IndexedDB và đồng bộ lại; hiển thị "Đã lưu / Đang lưu / Mất kết nối".
- **Quay lại:** mỗi lần mở lại lượt đang làm (tải lại, tab mới, máy khác) tăng `resume_count`; mất mạng tạm thời mà tab vẫn mở không tính. Vượt N (theo đề, mặc định 2) thì tự nộp, đặt `interrupted = true`.
- **Chuyển phần:** theo quy tắc bản thi trên máy tính; không quay lại phần đã kết thúc; hết giờ phần thì tự chuyển.
- **Listening:** audio phát một lần, không tạm dừng/tua; đoạn đã phát không cấp lại.
- **Writing:** soạn thảo có đếm từ, không kiểm tra chính tả của trình duyệt.
- **Speaking:** phát audio câu hỏi + chữ, đếm ngược chuẩn bị, ghi âm đúng thời lượng (MediaRecorder, webm/opus), tự dừng, upload ngay, tự thử lại khi lỗi; kiểm tra micro trước khi vào phần Speaking.
- **Nộp:** bởi người dùng hoặc job `auto_submit_attempt` khi hết giờ; idempotent; phát event `AttemptSubmitted`.

---

## 10. Chấm điểm

### 10.1 Câu khách quan
Bộ chấm theo kiểu tương tác; `GAP_TYPED`/`SHORT_ANSWER` chuẩn hoá khoảng trắng, chữ hoa/thường, chấp nhận các biến thể khai báo trong đáp án; giới hạn số từ theo đề ("NO MORE THAN TWO WORDS") vi phạm thì sai.

### 10.2 Quy đổi
Bảng mặc định theo (kỳ thi, kỹ năng) + bảng ghi đè theo đề; lưu phiên bản đã dùng. Hàm quy đổi cấu hình được: `TABLE` (raw → scaled), `IELTS_OVERALL`, `IELTS_WRITING_WEIGHTED`, `SUM` (TOEIC tổng).

### 10.3 Writing/Speaking
- Chính sách chấm lấy theo thứ tự ưu tiên: đề > tổ chức > kỳ thi. Bên chấm theo cấu hình tổ chức (giáo viên trung tâm hoặc English9x); doanh nghiệp và B2C luôn là English9x.
- **AI:** job `ai_grade` lấy skill chấm đang xuất bản của dạng bài; gửi Gemini rubric, bài mẫu, đề bài, bài làm (Speaking: file audio); yêu cầu JSON theo schema của skill (điểm từng tiêu chí, band/điểm, nhận xét, lỗi kèm vị trí, transcript với Speaking). Lưu `ai_evaluation` (phiên bản skill, model, token, chi phí). Ở chế độ "AI" là điểm chính thức; ở "Cả hai" là điểm sơ bộ.
- **Giáo viên:** tạo `grading_queue_item` theo (kỳ thi, bên chấm). Giáo viên được giao kỳ thi đó tự nhận; bài đã nhận ẩn với người khác; hạn nhận mặc định 24 giờ, quá hạn job trả về hàng chờ; quản trị chuyển bài được. Màn hình chấm: bài làm/audio, kết quả AI (nếu có), rubric theo tiêu chí, nhận xét. Điểm giáo viên là chính thức, thay điểm AI; lưu độ lệch AI–GV.
- **Lỗi Gemini:** timeout/429/5xx thử lại tối đa 3 lần (chờ 30 giây, 2 phút, 10 phút); JSON sai schema thử lại 1 lần kèm thông báo lỗi; vẫn lỗi thì chuyển `AI_FAILED`, báo quản trị, quản trị chấm lại hoặc chuyển giáo viên. Học viên chỉ thấy "Đang chấm".
- **Skill chấm:** có chế độ "Chạy thử" trong CMS với bài làm mẫu, không lưu điểm. Lời gọi Gemini qua interface `AiGrader`; API key trong biến môi trường.

### 10.4 Kết quả
Khi đủ điểm, phát `AttemptScored`. Trang kết quả: điểm dự đoán tổng và từng kỹ năng/phần; đúng/sai từng câu, đáp án, giải thích, transcript; link bài phương pháp của dạng câu; W/S theo tiêu chí + nhận xét, nhãn "sơ bộ"/"chính thức"; bảng tỉ lệ đúng theo dạng câu qua các lần thi; gợi ý bài ôn (các dạng câu có tỉ lệ đúng thấp nhất có bài phương pháp trong khoá học viên có quyền). Bài gián đoạn có nhãn cảnh báo.

---

## 11. Gói và thanh toán (B2C)

- `plans`: tên, giá (VND), số tháng, số khoá tối đa, trạng thái bán.
- Đặt gói: chọn gói → chọn khoá (đã xuất bản, ≤ số tối đa) → tạo `order` `PENDING_PAYMENT`, khoá giá.
- QR: sinh trên server theo chuẩn VietQR (EMVCo, có số tiền và nội dung) từ `payment_accounts` do quản trị cấu hình. Nội dung: `E9X <mã đơn 6 ký tự> <mã khoá>…`, chữ in hoa không dấu, ≤ 50 ký tự; vượt giới hạn thì chỉ mã đơn. Trang thanh toán: QR, số tiền, nội dung, nút sao chép, ghi chú chờ xác nhận.
- Quản trị: danh sách đơn chờ (lọc, tìm mã đơn); **Xác nhận** (ghi mã giao dịch) hoặc **Từ chối** (lý do). Xác nhận tạo `entitlements` cho từng khoá, hạn tính từ lúc xác nhận. Đơn chờ quá 7 ngày chuyển `EXPIRED`. Mọi thao tác ghi `audit_log`.
- Mua thêm gói tạo đơn mới; các gói chạy độc lập.
- Thông báo: quản trị khi có đơn mới; học viên khi xác nhận/từ chối; học viên khi gói còn 7 ngày và khi hết hạn.

---

## 12. B2B

- **Tổ chức:** quản trị hệ thống tạo, chọn `CENTER` hoặc `ENTERPRISE`, mời `ORG_ADMIN`.
- **Gói suất:** `org_license` (số suất, số tháng, danh sách khoá, giá, nguồn `QR` hoặc `CONTRACT` kèm số hợp đồng). QR dùng luồng đơn như mục 11.
- **Gán/thu hồi suất:** `ORG_ADMIN` gán suất + chọn khoá trong danh sách được phép → `entitlement` nguồn tổ chức; thu hồi kết thúc entitlement ngay, suất trở lại kho.
- **Thành viên:** mời email, import Excel (email, họ tên, lớp), mã/link tham gia theo tổ chức hoặc lớp (hạn dùng, giới hạn lượt).
- **Cấu hình chấm (trung tâm):** bên chấm (giáo viên trung tâm / English9x) và chế độ chấm cho học viên của trung tâm.
- **Nội dung riêng (trung tâm):** `AUTHOR`/`REVIEWER` của trung tâm, theo kỳ thi; chỉ học viên trung tâm thấy; đề xuất lên kho chung theo mục 7.1.
- **Lớp/nhóm:** giáo viên phụ trách, học viên; gán khoá cho cả lớp (dùng suất của từng người).
- **Giao bài:** bài học, kiểm tra chương hoặc đề thi thử, có hạn; trạng thái đã nộp/trễ/chưa nộp.
- **Lịch học:** buổi offline (phòng) hoặc online (link họp ngoài); nhắc lịch qua kênh đã bật.
- **Điểm danh tay:** Có mặt / Vắng / Muộn / Có phép, ghi chú.
- **Báo cáo:** theo lớp và học viên: tiến độ, bài giao, điểm, điểm yếu theo dạng câu, chuyên cần, thời gian học; xuất Excel.
- **Doanh nghiệp:** gọi lớp là "Nhóm"; không có giáo viên/nội dung riêng; bài W/S chấm bởi English9x.

---

## 13. Thông báo

| Sự kiện | Người nhận |
|---|---|
| Đơn mới chờ xác nhận | SYS_ADMIN |
| Đơn được xác nhận / bị từ chối | Học viên hoặc ORG_ADMIN |
| Gói còn 7 ngày / đã hết hạn | Học viên |
| Có điểm AI sơ bộ / điểm chính thức | Học viên |
| Bài mới trong hàng chờ / bài nhận sắp quá hạn | TEACHER |
| Nội dung chờ duyệt | REVIEWER |
| Bị yêu cầu sửa / đã xuất bản | AUTHOR |
| Bài được giao mới / sắp đến hạn | Học viên |
| Nhắc buổi học (trước 1 giờ) | Học viên, giáo viên |
| Chấm AI lỗi | SYS_ADMIN |

Kênh: trong app (luôn bật), email, Telegram. `notification_prefs` theo (người, sự kiện, kênh). Liên kết Telegram: bot nhận `/start <token một lần>` và lưu `chat_id`. Gửi qua job, thử lại khi lỗi; kênh là interface `NotificationChannel`.

---

## 14. Giao diện

- **Phong cách "Bàn học biên tập":** khu học tập nền giấy ấm, mực xanh đậm, nhấn cam san hô, bố cục kiểu tạp chí, hộp mẹo/bẫy như highlight, tiến độ dạng bản đồ chương. Phòng thi trung tính, tối giản, sát máy thi thật, đồng hồ cảnh báo ở 5 và 1 phút. CMS/quản trị dày thông tin, ưu tiên bảng và thao tác nhanh. Font: Be Vietnam Pro (giao diện), Lora (đoạn đọc, bài phương pháp). Sáng/tối bằng design token.
- Mockup các màn hình chính được dựng trong bước thiết kế frontend (P1) theo hướng UI-UX Pro Max và đưa vào `docs/superpowers/design/`.
- **Màn hình theo khu:**
  - Chung: trang chủ/danh mục khoá, giới thiệu khoá, đăng ký/đăng nhập (email, Google), chuyển ngữ cảnh "Cá nhân / Tổ chức", trung tâm thông báo, tuỳ chọn thông báo, kết nối Telegram.
  - Học viên: khoá của tôi; bản đồ chương; trang bài học; kiểm tra chương; phòng thi (Reading chia đôi màn hình, Listening, Writing đếm từ, Speaking ghi âm + đếm ngược); kết quả và chữa bài; điểm yếu theo dạng câu; **tiến trình học tập theo gói** (mục 8); bài được giao; lịch học; chọn gói và thanh toán QR; đơn hàng.
  - CMS: hàng việc của tôi (nháp, bị yêu cầu sửa, chờ tôi duyệt); ngân hàng câu hỏi; form theo dạng câu + xem trước; import Excel + lỗi theo dòng; dựng đề; trình soạn bài theo khối; dựng khoá (kéo thả chương/bài, luật mở khoá); bảng quy đổi; skill chấm + chạy thử; màn hình duyệt có so sánh khác biệt giữa hai phiên bản.
  - Giáo viên: hàng chờ chấm; màn hình chấm (bài làm/audio + gợi ý AI + rubric theo tiêu chí).
  - Tổ chức: tổng quan suất; thành viên (mời, import, mã tham gia); lớp/nhóm; giao bài; lịch và điểm danh; tiến trình từng học viên; báo cáo + xuất Excel; cấu hình chấm.
  - Quản trị hệ thống: tổ chức; gói B2C và gói suất; đơn chờ xác nhận; tài khoản nhận tiền; phân quyền theo kỳ thi; chính sách chấm; giám sát chấm AI; kho chung chờ duyệt; nhật ký.
- Điện thoại: học và luyện đầy đủ; thi thử IELTS Reading/Writing khuyến nghị ≥ 768 px (cảnh báo, không chặn); CMS/quản trị cho máy tính.
- Phím tắt phòng thi; WCAG 2.1 AA; song ngữ giao diện; mọi việc chạy nền có trạng thái tiến trình.

---

## 15. Phi chức năng

- **Hiệu năng:** 50 người thi đồng thời; p95 lưu đáp án ≤ 300 ms; p95 API đọc ≤ 500 ms.
- **Bảo mật:** OWASP Top 10; bcrypt; JWT 15 phút + refresh 30 ngày xoay vòng; rate limit đăng nhập (10 lần/15 phút/IP+email); RLS; presigned URL có hạn; kiểm tra loại và kích thước file upload; không log nội dung bài làm và khoá API; tuân thủ Nghị định 13/2023 (đồng ý xử lý dữ liệu khi đăng ký, xuất và xoá dữ liệu cá nhân theo yêu cầu).
- **Sao lưu:** hằng ngày, giữ 14 bản; tài liệu khôi phục được thử trong CI bằng một bản sao lưu mẫu.
- **Giám sát:** log JSON, endpoint health, trang quản trị theo dõi job lỗi, chi phí Gemini theo ngày, độ lệch AI–GV.

### 15.1 Thông số tự đặt (giả định, đều cấu hình được)
Các giá trị dưới đây do nhóm phát triển đặt khi viết spec, **chưa được chủ dự án quyết định**. Mỗi giá trị là một khoá cấu hình (`application.yml` / biến môi trường); quản trị đổi được những khoá có ghi "Quản trị".

| Khoá cấu hình | Mặc định (giả định) | Ý nghĩa | Đổi ở |
|---|---|---|---|
| `e9x.grading.ai.per-user-daily-limit` | `0` = **không giới hạn** | Gói không giới hạn lượt chấm (quyết định của chủ dự án). Chỉ là công tắc khẩn cấp nếu bị lạm dụng; mặc định tắt | Quản trị |
| `e9x.grading.ai.retry-delays` | `30s,2m,10m` | Thời gian chờ giữa các lần thử lại khi Gemini lỗi | Cấu hình |
| `e9x.grading.ai.schema-retry` | `1` | Số lần thử lại khi Gemini trả JSON sai schema | Cấu hình |
| `e9x.grading.claim-timeout` | `24h` | Hạn giáo viên giữ bài đã nhận | Quản trị |
| `e9x.grading.claim-due-warning` | `2h` | Giáo viên được nhắc (`GRADING_CLAIM_DUE`) khi bài đã nhận sắp quá hạn trong khoảng này (P4.3) | Cấu hình |
| `e9x.grading.claim-job-interval` | `5m` | Chu kỳ của việc nền `release_expired_claim`: trả bài quá hạn về hàng chờ và nhắc bài sắp quá hạn (P4.3) | Cấu hình |
| `e9x.grading.default-mode` | `AI` | Chế độ chấm Writing/Speaking (`AI` / `TEACHER` / `BOTH`) khi kỳ thi chưa có dòng chính sách riêng; dòng chính sách của kỳ thi do quản trị đặt (§10.3) luôn thắng giá trị này | Cấu hình |
| `e9x.exam.default-max-resumes` | `2` | N lần quay lại mặc định khi đề không đặt riêng | Quản trị |
| `e9x.exam.autosave-interval` | `5s` | Chu kỳ tự lưu đáp án | Cấu hình |
| `e9x.exam.late-sync-grace` | `2m` | Thời gian tối đa sau khi hết giờ phần thi để đồng bộ đáp án đã ghi offline trước giờ kết thúc | Cấu hình |
| `e9x.exam.recording-upload-tolerance` | `10s` | Dung sai tải bản ghi âm Speaking sau khi hết cửa sổ part | Cấu hình |
| `e9x.exam.max-recording-size` | `25MB` | Kích thước tối đa một bản ghi âm Speaking | Cấu hình |
| `e9x.exam.max-answer-size` | `64KB` | Kích thước tối đa một đáp án (JSON) | Cấu hình |
| `e9x.billing.order-expiry` | `7d` | Đơn chờ quá hạn thì `EXPIRED` | Quản trị |
| `e9x.billing.transfer-note-max-length` | `50` | Độ dài tối đa nội dung chuyển khoản | Cấu hình |
| `e9x.billing.expiry-reminder` | `7d` | Báo trước khi gói hết hạn | Quản trị |
| `e9x.billing.job-interval` | `1h` | Chu kỳ của ba việc nền `expire_orders`, `expire_entitlements`, `plan_expiry_reminder` (P5.1) | Cấu hình |
| `e9x.billing.expired-notice-window` | `14d` | `expire_entitlements` chỉ báo "gói đã hết hạn" cho gói hết hạn trong khoảng này; hết hạn cũ hơn không báo nữa (P5.1) | Cấu hình |
| `e9x.content.import.max-rows` | `2000` | Số dòng tối đa mỗi lô Excel | Quản trị |
| `e9x.content.import.max-media-size` | `500MB` | Dung lượng ZIP media tối đa mỗi lô | Quản trị |
| `e9x.media.url-ttl` | `10m` | Hạn của link media | Cấu hình |
| `e9x.media.storage.endpoint` | không có mặc định | Địa chỉ MinIO mà ứng dụng gọi (trong mạng Compose: `http://minio:9000`); để trống = tắt media (tải lên trả 503) | Cấu hình (biến môi trường `E9X_MINIO_ENDPOINT`) |
| `e9x.media.storage.public-url` | `e9x.public-base-url` | Địa chỉ công khai mà link media được ký cho và trình duyệt gọi (qua cùng origin, Caddy chuyển `/<bucket>/<id>` tới MinIO và giữ nguyên Host đã ký) | Cấu hình |
| `e9x.media.storage.bucket` | `e9x-media` | Tên bucket riêng tư (cũng là đường dẫn Caddy chuyển tiếp) | Cấu hình (`E9X_MINIO_BUCKET`) |
| `e9x.media.storage.access-key` / `secret-key` | không có mặc định | Khoá riêng của ứng dụng, chỉ có quyền trên bucket này (không phải tài khoản gốc của MinIO) | Cấu hình (biến môi trường `E9X_MINIO_ACCESS_KEY`, `E9X_MINIO_SECRET_KEY`) |
| `e9x.media.storage.ensure-bucket` | `true` | Tạo bucket khi khởi động nếu chưa có (idempotent) | Cấu hình |
| `e9x.media.ffprobe-path` | `ffprobe` | Công cụ đọc độ dài và loại luồng của audio/video đã tải lên; file mà ffprobe không đọc được bị từ chối | Cấu hình (`E9X_FFPROBE_PATH`) |
| `e9x.media.probe-timeout` | `30s` | Thời gian tối đa cho một lần đọc bằng ffprobe | Cấu hình |
| `e9x.auth.access-token-ttl` | `15m` | Hạn access token | Cấu hình |
| `e9x.auth.refresh-token-ttl` | `30d` | Hạn refresh token | Cấu hình |
| `e9x.auth.login-rate-limit` | `10/15m` | Giới hạn đăng nhập sai theo IP + email | Cấu hình |
| `e9x.auth.no-consent-retention` | `7d` | Tài khoản Google bỏ dở ở bước đồng ý (chưa có đồng ý, không có hoạt động) bị xoá sau thời gian này; tối thiểu 1 ngày | Cấu hình |
| `e9x.org.invitation-ttl` | `7d` | Hạn dùng của liên kết lời mời (tài khoản nội bộ và thành viên tổ chức); dùng một lần | Cấu hình |
| `e9x.org.invitation-hourly-limit` | `50` | Số lời mời (email) mỗi người được gửi trong một giờ; chặn việc dùng biểu mẫu mời để gửi thư rác | Cấu hình |
| `e9x.org.join.ip-rate-limit` | `30/15m` | Số lần thử mã tham gia không thành công mỗi địa chỉ IP (mã 8 ký tự ít entropy; lớp học chung một IP nên rộng hơn); thành công thì hoàn lại lượt | Cấu hình |
| `e9x.org.join.user-rate-limit` | `10/15m` | Số lần thử mã tham gia không thành công mỗi tài khoản | Cấu hình |
| `e9x.classroom.due-soon` | `24h` | Báo học viên chưa nộp trước hạn giao bài bao lâu (`ASSIGNMENT_DUE_SOON`, P6.2) | Cấu hình |
| `e9x.classroom.job-interval` | `1h` | Chu kỳ việc nền `assignment_due_soon` (P6.2) | Cấu hình |
| `e9x.audit.default-page-size` | `50` | Số bản ghi mỗi trang của màn hình nhật ký khi yêu cầu không nói; từ 1 đến `e9x.audit.max-page-size` | Cấu hình |
| `e9x.audit.max-page-size` | `100` | Số bản ghi tối đa một yêu cầu đọc nhật ký được xin (xin nhiều hơn thì trả đúng số này); từ 1 đến 1000 | Cấu hình |
| `e9x.audit.max-detail-bytes` | `4096` | Dung lượng JSON tối đa của phần chi tiết một bản ghi nhật ký (cận của CSDL: 16384); chi tiết quá cỡ làm hành động thất bại thay vì bị cắt | Cấu hình |
| `e9x.workflow.max-bundle-size` | `2000` | Số phiên bản tối đa trong một cụm gửi duyệt (`review_bundle`), từ 1 đến 5000 (cận của CSDL; giá trị ngoài khoảng làm ứng dụng không khởi động); không nhỏ hơn `e9x.content.import.max-rows`, vì một lô import Excel gửi duyệt thành một cụm | Cấu hình |
| `e9x.learning.streak.min-minutes` | `10` | Số phút học để tính một ngày trong chuỗi | Quản trị |
| `e9x.learning.heartbeat-interval` | `30s` | Chu kỳ gửi tín hiệu đang học | Cấu hình |
| `e9x.learning.idle-timeout` | `2m` | Không tương tác quá thời gian này thì ngừng tính giờ | Cấu hình |
| `e9x.notification.session-reminder` | `1h` | Nhắc trước buổi học | Quản trị |
| `e9x.notification.telegram.api-base` | `https://api.telegram.org` | Địa chỉ Bot API (E2E trỏ tới fake Telegram) | Cấu hình |
| `e9x.notification.telegram.bot-token` | không có mặc định | Token của bot; để trống = tắt kênh Telegram. Không bao giờ vào log, thân lỗi hay mã nguồn | Cấu hình (biến môi trường) |
| `e9x.notification.telegram.bot-username` | không có mặc định | Tên bot, dùng dựng liên kết `https://t.me/<tên>?start=<token>` | Cấu hình |
| `e9x.notification.telegram.webhook-secret` | không có mặc định | Giá trị Telegram gửi lại trong `X-Telegram-Bot-Api-Secret-Token`; webhook từ chối mọi lời gọi thiếu hoặc sai (so sánh thời gian hằng); để trống = từ chối tất cả | Cấu hình (biến môi trường) |
| `e9x.notification.telegram.link-ttl` | `15m` | Hạn của token liên kết Telegram một lần (256 bit, lưu dạng băm) | Cấu hình |
| `e9x.notification.mail-from` | `English9x <no-reply@english9x.vn>` | Người gửi của email thông báo (thư bảo mật dùng `e9x.auth.mail-from`, cùng giá trị mặc định) | Cấu hình |
| `e9x.events.redelivery-after` | `2m` | Event module mà listener chưa hoàn tất được gửi lại khi đủ tuổi này (giao ít nhất một lần, §6) | Cấu hình |
| `e9x.events.publication-max-age` | `30d` | Event module chưa hoàn tất quá tuổi này bị ghi log lỗi và xoá, để một dòng hỏng không giữ dữ liệu cá nhân mãi (§6) | Cấu hình |
| `e9x.backup.retention` | `14` | Số bản sao lưu giữ lại | Cấu hình |
| `e9x.grading.ai.daily-cost-alert` | `500000` (VND/ngày) | Ngưỡng chi phí Gemini mỗi ngày để cảnh báo quản trị (chỉ cảnh báo, không chặn) | Quản trị |
| `e9x.gemini.model` | `gemini-2.5-flash` | Model dùng để chấm | Cấu hình |
| `e9x.gemini.timeout` | `60s` | Thời gian chờ mỗi lần gọi | Cấu hình |
| `e9x.gemini.price-per-1k-input` / `-output` | theo bảng giá hiện hành | Dùng để ước tính chi phí hiển thị | Cấu hình |
| `e9x.content.media.max-upload-size` | `200MB` | Dung lượng tối đa một file media | Cấu hình |
| `e9x.content.question.max-json-size` | `256KB` | Dung lượng tối đa của phần nội dung và (riêng) của đáp án trong một phiên bản câu hỏi hay nhóm câu (P2.3); quá cỡ thì bị từ chối 422 theo đường dẫn | Cấu hình |
| `e9x.content.question.default-page-size` / `max-page-size` | `50` / `100` | Số câu mỗi trang của danh sách câu hỏi trong CMS khi yêu cầu không nói / nhiều nhất được xin (P2.3) | Cấu hình |
| `e9x.content.question.max-group-members` | `100` | Số câu tối đa trong một phiên bản nhóm câu (P2.3) | Cấu hình |
| `e9x.content.test.default-page-size` / `max-page-size` | `50` / `100` | Số đề mỗi trang của danh sách đề trong CMS khi yêu cầu không nói / nhiều nhất được xin (P2.6) | Cấu hình |
| `e9x.content.test.max-groups-per-part` | `100` | Số nhóm câu tối đa trong một phần của đề (P2.6), từ 1 đến 500 | Cấu hình |
| `e9x.content.strategy.max-json-size` | `512KB` | Dung lượng tối đa của phần khối (`blocks`) trong một phiên bản bài phương pháp (P2.4); quá cỡ thì bị từ chối 422 | Cấu hình |
| `e9x.content.strategy.max-blocks` | `200` | Số khối tối đa trong một phiên bản bài phương pháp (P2.4), từ 1 đến 1000 | Cấu hình |
| `e9x.content.strategy.video-hosts` | `youtube.com,www.youtube.com,m.youtube.com,youtu.be,vimeo.com,www.vimeo.com,player.vimeo.com` | Các host được phép trong địa chỉ của khối `video-embed` (chỉ https; host khác bị từ chối 422 theo đường dẫn `blocks[i].url`). `e9x.security.csp` có `frame-src` đúng hai nguồn `https://www.youtube.com https://player.vimeo.com`; thêm host nhúng khác phải sửa cả hai chỗ (P2.4) | Cấu hình |

---

## 16. Kiểm thử

### 16.1 Các tầng
- Unit: bộ chấm từng kiểu tương tác, quy đổi (TOEIC, IELTS overall, Writing có trọng số), luật mở khoá, quy tắc quyền học, payload VietQR (kiểm tra CRC), kiểm tra Excel, chuỗi ngày học.
- Tích hợp (Testcontainers PostgreSQL + MinIO): RLS, luồng duyệt, hàng đợi, import, chấm với `fake-gemini`.
- Hợp đồng Gemini: chạy với API thật khi có `GEMINI_API_KEY` (job CI riêng, không chặn merge).
- Kiến trúc: Spring Modulith verify.
- Frontend: Vitest + Testing Library; Storybook + axe.
- Rò rỉ dữ liệu: test sinh tự động gọi mọi endpoint bằng người dùng tổ chức A với ID dữ liệu tổ chức B, mong đợi 403/404.
- Tải: k6, 50 người thi đồng thời.

### 16.2 E2E: mọi tính năng đều có kịch bản
Playwright chạy trên Docker Compose với database sạch, `mailpit`, `fake-telegram`, `fake-gemini`. Mỗi tính năng dưới đây có ít nhất một kịch bản trong `e2e/specs/<khu>/`; CI chặn PR nếu thiếu kịch bản cho tính năng nào trong ma trận.

| ID | Tính năng | Kịch bản E2E chính |
|---|---|---|
| F01 | Đăng ký email + xác thực | Đăng ký → email trong mailpit → bấm link → đăng nhập được; chưa xác thực thì không mua được gói |
| F02 | Đăng nhập Google | Đăng nhập qua OAuth giả lập → tạo tài khoản → lần sau dùng lại tài khoản |
| F03 | Quên mật khẩu | Yêu cầu → link → đặt mật khẩu mới → mật khẩu cũ bị từ chối; refresh token cũ bị thu hồi |
| F04 | Mời tài khoản nội bộ | SYS_ADMIN mời AUTHOR qua email → nhận lời mời → có đúng quyền |
| F05 | Tạo tổ chức | SYS_ADMIN tạo trung tâm và doanh nghiệp → mời ORG_ADMIN → doanh nghiệp không có menu nội dung/giáo viên |
| F06 | Phân quyền theo kỳ thi | AUTHOR IELTS không thấy/không sửa được câu TOEIC; REVIEWER không duyệt được bản mình soạn |
| F07 | Chuyển ngữ cảnh | Người thuộc 2 tổ chức chuyển ngữ cảnh → danh sách khoá/lớp đổi theo |
| F08 | Cách ly dữ liệu | Người tổ chức A mở URL lớp/khoá riêng của tổ chức B → 404 |
| F09 | Soạn câu hỏi theo form | Tạo câu cho từng dạng câu TOEIC và IELTS (31 dạng) → lưu nháp → dữ liệu sai schema bị chặn với thông báo rõ |
| F10 | Xem trước như học viên | Xem trước hiển thị giống phòng thi, không lộ đáp án |
| F11 | Import Excel | Tải mẫu → upload file có lỗi → nhận lỗi theo dòng + file tô đỏ → sửa → import thành công → lô ở trạng thái nháp |
| F12 | Media | Upload audio/ảnh/video → URL hết hạn sau thời hạn → người không có quyền không mở được |
| F13 | Dựng đề | Dựng đề TOEIC 200 câu và IELTS 4 kỹ năng từ câu đã xuất bản; đặt N lần quay lại; gắn bảng quy đổi ghi đè |
| F14 | Bài phương pháp | Soạn bài có đủ loại khối (văn bản, bảng, ảnh, audio, video upload, YouTube, mẹo, câu nhúng, ví dụ giải) → học viên thấy đúng |
| F15 | Bảng quy đổi | Tạo bảng mặc định + ghi đè → duyệt → kết quả thi dùng đúng bảng; sửa bảng không đổi kết quả cũ |
| F16 | Skill chấm | Soạn skill IELTS W Task 2 → chạy thử với bài mẫu → duyệt → dùng khi chấm |
| F17 | Luồng duyệt | Gửi duyệt → yêu cầu sửa (nhận xét) → sửa → duyệt → xuất bản; đề chưa xuất bản đủ câu thì không xuất bản được |
| F18 | Phiên bản | Sửa câu đã xuất bản → học viên đang thi vẫn thấy bản cũ → bản mới chỉ hiện sau khi duyệt |
| F19 | Dựng khoá + luật mở khoá | Dựng khoá 3 chương mỗi luật STRICT/COMPLETION/FREE → hành vi mở khoá đúng |
| F20 | Danh mục cho khách | Khách xem danh mục, giới thiệu, mục lục; mở bài học → yêu cầu mua |
| F21 | Kho chung | Trung tâm đề xuất khoá → English9x yêu cầu sửa → duyệt → học viên B2C chọn được trong gói |
| F22 | Học bài + luyện tập | Đọc phương pháp → làm luyện tập → thấy đúng/sai và giải thích ngay → bài đánh dấu hoàn thành |
| F23 | Kiểm tra chương | Dưới ngưỡng → chưa mở chương sau → làm lại → đạt → mở; vượt số lần làm lại bị chặn |
| F24 | Tiến trình theo gói | 2 gói (cá nhân + suất tổ chức) hiển thị đúng; thời gian học tăng; chuỗi ngày đúng; gói hết hạn → chỉ đọc; mua lại → nối tiếp |
| F25 | Thi thử TOEIC | Làm đủ 200 câu → điểm L/R/tổng đúng theo bảng |
| F26 | Thi thử IELTS | Làm đủ 4 kỹ năng (AC và GT) → band từng kỹ năng và overall đúng quy tắc làm tròn |
| F27 | Audio một lần | Đoạn đã phát không phát lại; không có điều khiển tua/dừng |
| F28 | Lưu đáp án/offline | Cắt mạng giữa bài → tiếp tục chọn đáp án → có mạng → mọi đáp án có trên server |
| F29 | Quay lại N lần | Tải lại 2 lần → làm tiếp đúng câu, đồng hồ theo server → lần 3 → tự nộp, nhãn gián đoạn |
| F30 | Hết giờ | Hết giờ phần → tự chuyển; hết giờ đề → tự nộp |
| F31 | Nộp idempotent | Nộp đồng thời từ 2 tab → một kết quả |
| F32 | Ghi âm Speaking | Kiểm tra micro → chuẩn bị → ghi âm tự dừng → upload; mất mạng khi upload → tự thử lại |
| F33 | Chấm tự động | Đáp án biến thể hợp lệ được chấp nhận; vượt giới hạn số từ bị tính sai |
| F34 | Chấm AI | Chế độ AI → điểm theo tiêu chí, nhận xét, transcript hiển thị |
| F35 | Hàng chờ giáo viên | GV nhận bài → GV khác không thấy → quá hạn → bài trở lại → quản trị chuyển bài |
| F36 | Chế độ "Cả hai" | Điểm sơ bộ hiện ngay → GV chấm → điểm chính thức thay thế, học viên được báo |
| F37 | Gemini lỗi | `fake-gemini` trả lỗi liên tục → `AI_FAILED` → quản trị được báo → chuyển giáo viên |
| F38 | Kết quả + điểm yếu | Trang kết quả đủ thành phần; bảng điểm yếu theo dạng câu qua 2 lần thi; gợi ý mở đúng bài phương pháp |
| F39 | Gói B2C | Chọn gói 2 khoá → không chọn được khoá thứ 3 → tạo đơn |
| F40 | Đơn VietQR | QR giải mã ra đúng tài khoản, số tiền, nội dung; xác nhận → khoá mở; từ chối → học viên thấy lý do; quá 7 ngày → hết hạn |
| F41 | Quyền học | Entitlement hết hạn → không vào học được nhưng xem được kết quả cũ |
| F42 | Gói suất tổ chức | Kích hoạt theo hợp đồng và theo QR → số suất đúng |
| F43 | Gán/thu hồi suất | Gán → học viên học được → thu hồi → mất quyền, suất trở lại |
| F44 | Thành viên | Mời email; import Excel 20 người; mã tham gia hết lượt bị từ chối |
| F45 | Lớp/nhóm | Tạo lớp, thêm học viên, gán khoá cho cả lớp |
| F46 | Giao bài | Giao đề thi thử có hạn → học viên thấy → nộp trễ được đánh dấu trễ |
| F47 | Lịch học | Tạo buổi online có link và buổi offline → học viên thấy lịch → nhận nhắc |
| F48 | Điểm danh | Điểm danh 4 trạng thái → tỉ lệ chuyên cần trong báo cáo |
| F49 | Báo cáo + Excel | Xuất Excel lớp → nội dung khớp màn hình |
| F50 | Cấu hình chấm trung tâm | Chọn GV trung tâm → bài vào hàng chờ trung tâm; chọn English9x → vào hàng chờ chung |
| F51 | Nội dung riêng trung tâm | AUTHOR/REVIEWER trung tâm soạn, duyệt → chỉ học viên trung tâm thấy |
| F52 | Thông báo trong app | Sự kiện tạo thông báo, đánh dấu đã đọc |
| F53 | Email | Email đúng sự kiện xuất hiện trong mailpit; tắt email cho sự kiện → không gửi |
| F54 | Telegram | Liên kết bot bằng token → `fake-telegram` nhận tin đúng sự kiện; token dùng lại bị từ chối |
| F55 | Tuỳ chọn kênh | Bật/tắt theo sự kiện × kênh có hiệu lực |
| F56 | Giám sát AI | Trang quản trị hiện job lỗi, chi phí theo ngày, độ lệch AI–GV |
| F57 | Nhật ký | Xác nhận đơn, thu hồi suất, duyệt nội dung đều có trong audit log |
| F58 | Song ngữ + truy cập | Đổi ngôn ngữ giao diện; quét axe các màn hình chính không có lỗi serious/critical |

### 16.3 Definition of Done của v1
- CI xanh: build, unit, tích hợp, kiến trúc, lint, rò rỉ dữ liệu, **toàn bộ E2E F01–F58**.
- Coverage ≥ 80% cho `exam`, `grading`, `billing`, `org`.
- Smoke tải đạt mục 15.
- `docker compose up` từ README; tài liệu deploy VPS và khôi phục sao lưu.
- Mỗi kết luận "đã xong" có bằng chứng (output lệnh, ảnh Playwright, video E2E của luồng chính).

---

## 17. Dự án con và cách triển khai

| # | Dự án con | Phụ thuộc | Tính năng |
|---|---|---|---|
| P1 | Nền móng: monorepo, CI, Docker Compose, `identity`, `org` + RLS, `workflow`, `notification`, hàng đợi, khung frontend + design system + mockup | — | F01–F08, F52–F55, F57, F58 |
| P2 | Nội dung: `catalog` (31 dạng câu v1), `content`, CMS, import Excel | P1 | F09–F18 |
| P3 | Học & thi: `course`, `learning`, `exam` | P2 | F19–F23, F25–F32 |
| P4 | Chấm & phân tích: `grading`, `analytics`, tiến trình theo gói | P3 (song song qua event contract) | F24, F33–F38, F56 |
| P5 | Thương mại: `billing` | P1, P2 | F39–F43 |
| P6 | B2B vận hành: `classroom`, cấu hình chấm trung tâm, nội dung riêng, kho chung | P1, P3 | F44–F51, F21 |

- Nhánh tích hợp `release/v1` từ `main`. Mỗi task: một git worktree + nhánh con; subagent mới làm theo TDD; subagent reviewer độc lập; đạt thì merge vào `release/v1`, chạy lại toàn bộ test, xoá worktree. Task trong cùng wave không sửa chung file; file dùng chung (build, router, migration index) chỉ sửa ở task nền.
- Hết mỗi dự án con: review toàn nhánh bởi reviewer chưa tham gia.
- PR `release/v1` → `main` kèm phạm vi, link spec/plan, kết quả test, ảnh màn hình, rủi ro. Không merge vào `main`.
- Mỗi dự án con có implementation plan riêng trong `docs/superpowers/plans/`.

---

## 18. Rủi ro và việc cần chuẩn bị

| Rủi ro / phụ thuộc | Cách xử lý |
|---|---|
| Chi phí Gemini không có hạn mức theo người (gói không giới hạn lượt chấm) | Giữ đúng quyết định: không giới hạn lượt chấm. Theo dõi chi phí theo ngày và theo người, cảnh báo quản trị khi vượt ngưỡng chi phí cấu hình; công tắc khẩn cấp `e9x.grading.ai.per-user-daily-limit` mặc định `0` (tắt) |
| Độ chính xác chấm AI | Skill chấm có bài mẫu theo band; đo độ lệch AI–GV; chế độ "Cả hai" cho đề quan trọng |
| Bảng quy đổi không chính thức | Ghi "điểm dự đoán"; bảng có duyệt và phiên bản |
| Format kỳ thi thay đổi | Dạng câu và cấu trúc là dữ liệu cấu hình có phiên bản |
| Xác nhận QR thủ công gây chờ | Thông báo Telegram tức thời cho quản trị; adapter thanh toán để thêm cổng tự động sau |
| Rò rỉ dữ liệu giữa tổ chức | RLS + test sinh tự động cho mọi endpoint |
| Phạm vi v1 lớn (B2C + B2B) | Chia 6 dự án con, chạy song song theo wave |
| Cần trước khi code | Quyền truy cập repo GitHub (liên kết GitHub hoặc kết nối thư mục repo trên máy); Gemini API key; tài khoản SMTP; Telegram bot token; VPS (khi deploy) |
