# P1 — Nền móng: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Monorepo chạy được end-to-end trên Docker Compose với tài khoản, tổ chức + RLS, quy trình duyệt dùng chung, hàng đợi nền, thông báo, nhật ký, khung frontend và hạ tầng E2E.

**Architecture:** Spring Boot 3 + Spring Modulith (`e9x-app`) gọi lõi `e9x-domain`; PostgreSQL 16 + RLS; React SPA phục vụ bởi app; Compose: app, postgres, minio, caddy, backup (+ mailpit, fake-telegram, fake-gemini cho dev/E2E).

**Tech Stack:** Java 21, Maven, Spring Boot 3.x, Spring Modulith, Spring Security (JWT, OAuth2 client), Flyway, springdoc-openapi, Testcontainers; React 18, Vite, TS strict, TanStack Query, Zustand, Tailwind, Radix, i18next, Storybook, axe; Playwright.

**Spec:** `docs/superpowers/specs/2026-11-06-english9x-design.md` (§4, §5, §6, §13, §14, §15, §16). Global Constraints và Review Focus: xem `2026-11-06-english9x-v1-overview.md` — áp dụng nguyên văn cho mọi task dưới đây.

---

### Task P1.1: Monorepo, build, CI, Docker Compose (W0)

**Files:**
- Modify: `backend/pom.xml` (thêm module `e9x-app`, Spring Boot BOM, Spring Modulith BOM, Testcontainers BOM)
- Create: `backend/e9x-app/pom.xml`, `backend/e9x-app/src/main/java/com/english9x/E9xApplication.java`, `backend/e9x-app/src/main/resources/application.yml` (mọi khoá §15.1 với giá trị mặc định), `backend/e9x-app/src/test/java/com/english9x/ModularityTest.java`
- Create: `frontend/package.json`, `frontend/vite.config.ts`, `frontend/tsconfig.json`, `frontend/src/main.tsx`
- Create: `e2e/package.json`, `e2e/playwright.config.ts`
- Create: `infra/docker-compose.yml`, `infra/docker-compose.e2e.yml`, `infra/Caddyfile`, `infra/backup/backup.sh`, `infra/fakes/{gemini,telegram}/server.mjs`
- Create: `.github/workflows/ci.yml`, `scripts/verify.sh`, `README.md`

**Interfaces:** Produces lệnh `./scripts/verify.sh` (backend `mvn -B verify`, frontend `pnpm -C frontend lint test build`, `node scripts/check-e2e-coverage.mjs`), `docker compose -f infra/docker-compose.yml up` chạy app ở `https://localhost`. Fake Gemini: `POST /v1beta/models/{model}:generateContent` trả JSON theo kịch bản đặt qua `POST /__scenario` (`ok` | `error500` | `badSchema`). Fake Telegram: `POST /bot{token}/sendMessage` lưu tin; `GET /__messages?chat_id=` trả danh sách.

**Tests:**
- `ModularityTest.verifiesModuleStructure` — `ApplicationModules.of(E9xApplication.class).verify()`.
- `ApplicationStartsIT.contextLoadsWithPostgres` — Testcontainers PostgreSQL 16.
- CI job: build, unit, IT, lint, gate E2E (cho phép fail ở W0–W9 dưới nhãn `coverage-progress`, bắt buộc pass ở W10).

- [ ] Chu trình chuẩn (overview). Bằng chứng: output `./scripts/verify.sh`, ảnh `docker compose ps` healthy.

### Task P1.2: Lõi domain + harness offline (W0) — **đã làm trong wave cục bộ** (L0, L1a–L1c)

Khi có mạng: chạy `mvn -pl e9x-domain test` để xác nhận cùng bộ test chạy bằng JUnit thật; sai khác giữa shim và JUnit là lỗi phải sửa trong shim.

### Task P1.3: DB baseline + RLS (W1) — phần SQL đã làm trong wave cục bộ (L1d)

**Files:**
- Create: `backend/e9x-app/src/main/java/com/english9x/org/internal/RlsSessionConfigurer.java` (đặt `set_config('app.org_id', ?, true)`, `app.user_id` ở đầu mỗi transaction qua `TransactionSynchronization` / `DataSource` wrapper)
- Create: `backend/e9x-app/src/test/java/com/english9x/org/RlsIT.java`, `backend/e9x-app/src/test/java/com/english9x/org/RlsLeakSweepIT.java`

**Interfaces:** Produces `CurrentContext` (`UUID userId()`, `Optional<UUID> orgId()`, `boolean isEnglish9xStaff()`, `boolean isOrgAdmin()`); ứng dụng kết nối bằng role `e9x_app` (Flyway chạy bằng role chủ `e9x_owner`). `RlsSessionConfigurer` đặt thêm `app.e9x_staff = 'on'` chỉ khi người dùng có vai trò English9x (AUTHOR/REVIEWER/TEACHER/SYS_ADMIN với `org_id` NULL) **và** ngữ cảnh là "Cá nhân"/English9x, và `app.org_admin = 'on'` khi người dùng là ORG_ADMIN của tổ chức trong ngữ cảnh (theo các ruling của L1d: ghi nội dung English9x, ghi thành viên/quyền, duyệt kho chung đều cần các cờ này). Module chấm dùng role `e9x_grader` và phải đặt `app.org_id` của lượt thi.

**Ghi chú từ review wave cục bộ:** `e9x_app` là NOLOGIN — tạo role đăng nhập `e9x_app_login` kế thừa `e9x_app`; thêm test `PooledConnectionFlagsIT.nextTransactionCarriesNoFlags` (cờ `app.e9x_staff`/`app.org_admin`/`app.org_id` đặt bằng `set_config(..., true)` không rò sang transaction kế tiếp trên cùng kết nối pool); bảng `entitlements` cần thêm policy INSERT/UPDATE cho luồng xác nhận đơn (P5) và gán suất (P6), và policy SELECT cho ORG_ADMIN/TEACHER xem tiến trình học viên trong tổ chức (spec §8).

**Tests:** `RlsIT` chạy lại kịch bản `backend/db-tests/*.sql` qua Flyway + Testcontainers; `RlsLeakSweepIT.everyGetEndpointRejectsForeignOrgIds` — liệt kê mọi endpoint GET có `{id}` từ OpenAPI, gọi bằng người tổ chức A với ID tổ chức B, mong đợi 403/404 (Review Focus #1).

### Task P1.4: identity (W1) — F01, F02, F03

> **Ghi chú 2026-11-07 (từ P1.3):** tra cứu đăng nhập theo email phải đi qua một hàm `SECURITY DEFINER` hẹp (chỉ trả những cột cần thiết cho đúng một email), không mở policy SELECT rộng trên `users`.

**Files:** `backend/e9x-app/src/main/java/com/english9x/identity/{api,internal}/**`, migration `V202611070001__identity_auth.sql` (`auth_identities`, `refresh_tokens`, `email_verifications`, `password_resets`), frontend `frontend/src/features/auth/**`, E2E `e2e/specs/auth/F01-register-verify.spec.ts`, `F02-google-login.spec.ts`, `F03-password-reset.spec.ts`.

**Interfaces (Produces):**
- `POST /api/auth/register {email, password, displayName, consent:true}` → 201; gửi email xác thực.
- `POST /api/auth/verify-email {token}`; `POST /api/auth/login {email, password}` → `{accessToken, refreshToken}`; `POST /api/auth/refresh {refreshToken}` (xoay vòng, token cũ vô hiệu); `POST /api/auth/password-reset/request {email}`; `POST /api/auth/password-reset/confirm {token, newPassword}` (thu hồi mọi refresh token); `GET /api/auth/oauth2/google` (Spring OAuth2 client; E2E dùng IdP giả lập).
- `identity.api.UserDirectory#findById(UUID)`, event `UserRegistered`.

**Bắt buộc thêm (hoãn từ wave cục bộ):** bật RLS cho `users` — SELECT: chính mình, hoặc cùng tổ chức trong ngữ cảnh hiện tại, hoặc staff; UPDATE: chính mình; INSERT chỉ qua luồng đăng ký (cờ `app.registration='on'`). Cập nhật test `RLS enabled on all tables` của L1d (bỏ `users` khỏi allowlist).

**Tests:** `RegistrationIT.unverifiedUserCannotCheckout` (khoá cờ `emailVerified` dùng ở P5), `LoginRateLimitIT.blocksAfterConfiguredAttempts` (`e9x.auth.login-rate-limit`), `RefreshRotationIT.reusedRefreshTokenRevokesFamily`, `PasswordResetIT.oldPasswordAndTokensRejected`, `GoogleLoginIT.linksExistingEmail`. E2E như ma trận F01–F03.

### Task P1.5: org + ngữ cảnh (W2) — F04, F05, F06, F07, F08

> **Ghi chú 2026-11-07 (từ P1.3):** mọi grant vai trò trong một tổ chức phải đi kèm một dòng `org_memberships` cho cùng người dùng và tổ chức, nếu không `RlsSessionConfigurer` từ chối ngữ cảnh (`OrgContextDeniedException`). Chỉ SYS_ADMIN thật (cờ + grant) hoặc ORG_ADMIN thật của chính tổ chức đó được ghi `role_grants`/`org_memberships`; cờ đơn lẻ không đủ.
>
> **Bắt buộc xử lý trong P1.5 (phát hiện khi review P1.3):**
> - `organizations` chưa có policy INSERT và SYS_ADMIN không thấy tổ chức mình không thuộc về: bước đầu của F05 và `POST /api/admin/organizations` đang bị chặn. Thêm policy cho SYS_ADMIN thật.
> - Chấp nhận lời mời và mã tham gia chưa có đường ghi: người được mời không có cờ nên bị từ chối ở `role_grants`/`org_memberships`. Cần hàm `SECURITY DEFINER` hẹp, kiểm tra token rồi mới ghi đúng vai trò trong lời mời.
> - Fixture kiểu trong leak sweep phải có thêm ca cha thuộc tổ chức mình + con thuộc tổ chức khác; sửa Javadoc và controller mẫu trong `SweepEndpoints`/`RlsLeakSweepIT`.

**Files:** `backend/e9x-app/src/main/java/com/english9x/org/**`, migration `V202611080001__org_invitations.sql` (`invitations`, `join_codes`), frontend `frontend/src/features/org/**`, `frontend/src/app/ContextSwitcher.tsx`, E2E F04–F08.

**Interfaces (Produces):**
- Header `X-Org-Context: <uuid>` (vắng = "Cá nhân"); filter kiểm tra thành viên → 403.
- `org.api.Authz#require(Role role, Optional<String> examCode)` ném `AccessDeniedException`; `org.api.Authz#canReviewOwnVersion` luôn false.
- `POST /api/admin/organizations {name, type}`; `POST /api/orgs/{id}/invitations {email, roles[]}`; `POST /api/invitations/{token}/accept`; `GET/PUT /api/orgs/{id}/role-grants`.
- Frontend: đổi ngữ cảnh xoá cache TanStack Query theo khoá `['ctx', orgId, …]` (Review Focus #1).

**Tests:** `AuthzTest.examScopedRoleDeniesOtherExam`, `InvitationIT.acceptGrantsRoles`, `EnterpriseOrgIT.cannotGrantAuthorReviewerTeacher`, `ContextSwitchIsolationIT.inFlightRequestUsesItsOwnContext`. E2E F04–F08.

### Task P1.6: workflow dùng chung (W2) — nền của F17, F18

**Files:** `backend/e9x-app/src/main/java/com/english9x/workflow/**`, migration `V202611080002__workflow_review.sql` (`review_requests`, `review_bundles`).

**Interfaces (Produces):**
- `workflow.api.Reviewable` (đối tượng có `ownerOrgId`, `examCode`, `versionId`, `authorId`, `dependencies(): List<VersionRef>`).
- `workflow.api.ReviewService`: `submit(VersionRef|BundleRef)`, `requestChanges(reviewId, comment)`, `approve(reviewId)` → phát `ContentPublished(VersionRef)`; từ chối approve khi phụ thuộc chưa PUBLISHED (`UnpublishedDependencyException` liệt kê phụ thuộc).
- Trạng thái dùng lại enum `ContentStatus { DRAFT, IN_REVIEW, CHANGES_REQUESTED, PUBLISHED, ARCHIVED }`.

**Tests:** `ReviewServiceIT.authorCannotApproveOwnVersion`, `.changesRequestedReturnsToDraftWithComment`, `.approveBlockedByUnpublishedDependency`, `.bundleApprovesAllOrNothing`, `.publishedVersionIsImmutable` (UPDATE bị trigger chặn).

### Task P1.7: Hàng đợi nền (W1)

> **Ghi chú 2026-11-07 (từ P1.3):** quyết định của controller: ngữ cảnh worker là một login role riêng `e9x_worker_login` với pool nhỏ riêng, chỉ các handler của `platform.jobs` dùng, không bao giờ truy cập được từ HTTP, không có GUC `app.system`, quyền hạn hẹp nhất có thể. Việc không có ngữ cảnh người dùng khiến job thấy 0 dòng; không giải quyết bằng cờ.

**Files:** `backend/e9x-app/src/main/java/com/english9x/platform/jobs/**`, migration `V202611070002__jobs.sql`.

**Interfaces (Produces):** `JobQueue#enqueue(String type, JsonNode payload, Instant runAt)`; `@JobHandler("type")` bean `JobResult handle(Job)`; worker poll `FOR UPDATE SKIP LOCKED`, `e9x.grading.ai.retry-delays` dạng chung `e9x.jobs.<type>.retry-delays`; bật/tắt bằng `app.worker.enabled`.

**Tests:** `JobQueueIT.twoWorkersNeverTakeSameJob`, `.retriesWithConfiguredDelays`, `.marksFailedAfterLastRetry`, `.disabledWorkerDoesNotPoll`.

### Task P1.8: notification (W2) — F52, F53, F54, F55

**Files:** `backend/e9x-app/src/main/java/com/english9x/notification/**`, migration `V202611080003__notifications.sql`, frontend `frontend/src/features/notifications/**`, E2E `e2e/specs/notify/F52…F55`.

**Interfaces (Produces):** `NotificationChannel { String id(); void send(Recipient, Message) }` với `InAppChannel`, `EmailChannel` (SMTP), `TelegramChannel` (Bot API, base URL cấu hình để trỏ fake). `NotificationService#notify(EventType, UUID userId, Map<String,Object> data)` tạo job `send_notification` cho mỗi kênh bật trong `notification_prefs` (in-app luôn bật). `POST /api/me/telegram/link` → `{deepLink}` với token một lần; webhook `POST /api/telegram/webhook` nhận `/start <token>`. Danh sách `EventType` theo bảng spec §13.

**Tests:** `NotificationServiceIT.respectsPrefsPerEventAndChannel`, `TelegramLinkIT.tokenSingleUse`, `EmailChannelIT.rendersVietnameseTemplate`. E2E F52–F55 (mailpit, fake-telegram).

### Task P1.9: Nhật ký (W2) — F57

**Files:** `backend/e9x-app/src/main/java/com/english9x/platform/audit/**`, migration `V202611080004__audit_log.sql`, trang `frontend/src/features/admin/AuditLogPage.tsx`, E2E `e2e/specs/admin/F57-audit-log.spec.ts`.

**Interfaces:** `AuditLog#record(String action, String targetType, String targetId, Map<String,Object> detail)`; bảng chỉ INSERT (trigger chặn UPDATE/DELETE). Các module P2/P5/P6 gọi khi duyệt nội dung, xác nhận/từ chối đơn, gán/thu hồi suất, đổi quyền.

**Tests:** `AuditLogIT.appendOnly`, `.recordsActorAndContext`.

### Task P1.10: Frontend shell, design system, i18n, a11y (W1) — F58 (+ nền F07)

**Files:** `frontend/src/app/**` (router nạp `routes/<khu>/*.routes.ts` tự động, layout 4 khu, guard theo vai trò), `frontend/src/design/**` (token màu/khoảng cách/font sáng-tối, Button, Input, Select, Dialog, Tabs, Toast, Table, Badge, EmptyState, Timer, AudioPlayer, Recorder shell, QuestionNavigator, Highlighter), `frontend/src/i18n/{vi,en}.json`, `frontend/.storybook/**`, `docs/superpowers/design/mockups/*.html` (8 màn chính: danh mục, bản đồ chương, bài học, phòng thi Reading, phòng thi Speaking, kết quả, CMS duyệt, đơn QR quản trị), E2E `e2e/specs/common/F58-i18n-a11y.spec.ts`.

**Interfaces:** `useApi()` (client sinh từ OpenAPI, tự gắn `Authorization`, `X-Org-Context`); `<RequireRole role exam?>`; theme "Bàn học biên tập" (spec §14), font Be Vietnam Pro + Lora (self-host trong `frontend/public/fonts`).

**Tests:** Vitest cho từng component; Storybook test-runner + axe (không có vi phạm serious/critical); E2E F58 quét axe 8 màn chính và đổi ngôn ngữ.

### Task P1.11: OpenAPI, lỗi RFC 7807, hợp đồng event (W1)

> **Ghi chú 2026-11-07 (từ P1.3):** `gen-api` (sinh client từ OpenAPI) phải chạy ứng dụng với `E9X_OPENAPI_ENABLED=true`; `/v3/api-docs` mặc định tắt.

**Files:** `backend/e9x-app/src/main/java/com/english9x/platform/web/ProblemDetailsAdvice.java`, `backend/e9x-app/src/main/java/com/english9x/*/api/events/*.java`, `frontend/scripts/gen-api.mjs`, `docs/superpowers/design/events.md`.

**Interfaces (Produces, cố định cho các wave sau):**
- `AttemptSubmitted(UUID attemptId, UUID userId, UUID testVersionId, Optional<UUID> orgId, Instant submittedAt, String reason, boolean interrupted)`
- `AttemptScored(UUID attemptId, UUID userId, Map<String, Double> skillScores, boolean official)`
- `ContentPublished(String type, UUID versionId, Optional<UUID> ownerOrgId, String examCode)`
- `OrderConfirmed(UUID orderId, UUID userId, List<UUID> courseIds, Instant confirmedAt)`; `OrderRejected(UUID orderId, String reason)`
- `SeatAssigned(UUID licenseId, UUID userId, List<UUID> courseIds)`; `SeatRevoked(UUID licenseId, UUID userId)`
- Lỗi: `application/problem+json` với `type`, `title`, `status`, `detail`, `errors[]` (theo trường/dòng).

**Tests:** `ProblemDetailsIT.validationErrorsListFields`; `ApiClientGenTest` (frontend) — client sinh ra biên dịch được.

### Task P1.12: Hạ tầng E2E + gate (W1) — gate đã làm trong wave cục bộ

**Files:** `e2e/fixtures/{auth,org,content,billing,seed}.ts` (tạo dữ liệu qua API admin seed `POST /api/test/seed/*`, chỉ bật ở profile `e2e`), `e2e/helpers/{mailpit,telegram,gemini}.ts`, `e2e/playwright.config.ts` (chạy trên `infra/docker-compose.e2e.yml`, DB sạch mỗi lần chạy, video cho luồng chính).

**Interfaces:** `seed.learnerWithPlan({courses})`, `seed.publishedToeicTest()`, `seed.publishedIeltsTest({variant:'AC'|'GT'})`, `seed.center({teachers, learners})`, `mailpit.lastEmailTo(email)`, `telegram.messagesFor(chatId)`, `gemini.setScenario('ok'|'error500'|'badSchema')`.

**Tests:** `e2e/specs/_smoke.spec.ts` (app lên, trang chủ hiển thị); gate `node scripts/check-e2e-coverage.mjs` báo tiến độ.
