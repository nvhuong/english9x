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

> **Ghi chú 2026-11-08 (từ P1.9):** quyền xuất và xoá dữ liệu cá nhân (spec §15) KHÔNG thuộc P1.4: xem ghi chú ở P6.5.

> **Ghi chú 2026-11-07 (từ P1.11):** 401/403 của Spring Security (entry point, access-denied handler, lỗi ném từ filter trước MVC) phải render cùng `application/problem+json` như `ProblemDetailsAdvice` (`type`, `title`, `status`, `detail`, `errors[]`, không lộ chi tiết nội bộ).

> **Ghi chú 2026-11-07 (P1.4 đã làm, xem `.superpowers/sdd/.../task-P1.4-report.md`):** (1) mọi thao tác trước khi có người dùng (đăng ký, tra cứu đăng nhập, xác thực, đặt lại, refresh, Google) đi qua các hàm `SECURITY DEFINER` hẹp `auth_*` trong `V202611070001__identity_auth.sql`; `e9x_app` không có quyền bảng nào trên `refresh_tokens`/`email_verifications`/`password_resets` và không INSERT/DELETE được `users`. Tạo tài khoản là hàm `auth_create_user` (không dùng cờ GUC `app.registration`). (2) Task sau xác thực thế nào: gửi `Authorization: Bearer <accessToken>`; filter đặt `CurrentContextHolder` cho người dùng (ngữ cảnh cá nhân), request có header `X-Org-Context` bị từ chối 403 cho tới khi **P1.5** thay đoạn đó bằng kiểm tra thành viên (`platform.security.CurrentContextFilter`). Endpoint công khai mới phải thêm vào danh sách `PUBLIC_POST/PUBLIC_GET` của `SecurityConfiguration` kèm test; mọi thứ khác dưới `/api/**` mặc định cần token. (3) E2E: `seed.verifiedLearner()`, `auth.loginAs(page, user)`, `auth.registerLearner(page, user)`; seed API cần header `X-E2E-Seed-Secret` (do `scripts/e2e.sh` tạo). (4) Chặn mua gói khi chưa xác thực: gọi `identity.api.UserDirectory#requireVerified(userId)` (xem ghi chú ở P5.1).

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

> **Ghi chú 2026-11-07 (P1.5 đã làm, xem `.superpowers/sdd/.../task-P1.5-report.md`):** (1) Header `X-Org-Context` được kiểm tra trong `CurrentContextFilter` (thành viên, một UUID chuẩn, một header; sai → 403 giống hệt nhau, không phân biệt "không có tổ chức" với "không phải thành viên"). (2) Task sau dùng quyền bằng `org.api.Authz#require(Role, Optional<String> examCode)` / `#has` (đọc dữ liệu vai trò trong một giao dịch của chính nó, chặt: SYS_ADMIN không phải AUTHOR, vai trò English9x không đi theo vào tổ chức) và `canReviewOwnVersion()` luôn false. (3) Mọi grant trong tổ chức đi cùng một dòng `org_memberships` (trigger CSDL chặn ngược lại); thứ tự: thêm membership TRƯỚC, xoá grant TRƯỚC. (4) Lời mời/mã tham gia ghi qua hàm `SECURITY DEFINER` hẹp (`org_invitation_accept`, `org_join_code_redeem`), không có tham số vai trò/tổ chức/người dùng. (5) Mã kỳ thi v1 là hằng `IELTS`, `TOEIC` ở `RoleRules.KNOWN_EXAMS` và `frontend/src/features/org/roles.ts`: **P2.1 thay bằng bảng `exams`**. (6) Endpoint mới có `{id}` GET đều nằm trong leak sweep qua `X-Org-Context` thật; thêm endpoint thì thêm hàng của tổ chức B vào `SweepFixtures`. (7) E2E: `seed.organization/member/staffGrant/sysAdmin/authz`, `org.switchContext/acceptInvitation/apiLogin/api`. (8) Quyết định cần chủ dự án xem: người đăng nhập khác email lời mời bị từ chối (403 `invitation-email-mismatch`), lời mời vẫn dùng được cho đúng người; email chưa xác thực phải xác thực trước (403 `email-not-verified`).

### Task P1.6: workflow dùng chung (W2) — nền của F17, F18

**Files:** `backend/e9x-app/src/main/java/com/english9x/workflow/**`, migration `V202611080002__workflow_review.sql` (`review_requests`, `review_bundles`).

**Interfaces (Produces):**
- `workflow.api.Reviewable` (đối tượng có `ownerOrgId`, `examCode`, `versionId`, `authorId`, `dependencies(): List<VersionRef>`).
- `workflow.api.ReviewService`: `submit(VersionRef|BundleRef)`, `requestChanges(reviewId, comment)`, `approve(reviewId)` → phát `ContentPublished(VersionRef)`; từ chối approve khi phụ thuộc chưa PUBLISHED (`UnpublishedDependencyException` liệt kê phụ thuộc).
- Trạng thái dùng lại enum `ContentStatus { DRAFT, IN_REVIEW, CHANGES_REQUESTED, PUBLISHED, ARCHIVED }`.

**Tests:** `ReviewServiceIT.authorCannotApproveOwnVersion`, `.changesRequestedReturnsToDraftWithComment`, `.approveBlockedByUnpublishedDependency`, `.bundleApprovesAllOrNothing`, `.publishedVersionIsImmutable` (UPDATE bị trigger chặn).

> **Ghi chú 2026-11-08 (P1.6 đã làm; sửa vòng 1 cùng ngày; việc cho P2.x, P1.8, P1.9, P4.1, P6.5):**
> - API công khai ở `com.english9x.workflow.api` (named interface `api`): `ReviewService`, `ReviewableType` (cổng mỗi loại nội dung triển khai, một bean mỗi loại), `Reviewable`, `VersionRef`, `BundleRef`, `ReviewView`, `ReviewQueueEntry`, `ContentStatus`. Hàng chờ `open(offset, limit)` trả một dòng cho mỗi cụm (kèm kích thước) và một dòng cho mỗi review lẻ; `members(bundleId, offset, limit)` có phân trang.
> - Ngoại lệ là `ErrorResponseException`, `ProblemDetailsAdvice` tự render problem+json: `ReviewNotFoundException` 404, `ReviewConflictException` 409, `UnpublishedDependencyException` 409 (thân có `dependencies: [{type, versionId}]`, với cụm là của mọi phần tử), `InvalidReviewInputException` 400, `SelfReviewException` là `AccessDeniedException` 403. P2.9 không cần tự ánh xạ.
> - Sự kiện (xem `events.md`): `ContentPublished(String type, UUID versionId, Optional<UUID> ownerOrgId, String examCode)` (không phải `ContentPublished(VersionRef)` của brief), thêm `ReviewRequested`, `ReviewChangesRequested`, `ReviewApproved` (hai loại sau có `authorId`) cho thông báo spec §13. Phát trong transaction duyệt, sau mọi thay đổi trạng thái; chưa nối event publication registry (P4.1).
> - Mỗi bảng `*_versions` của P2 gắn trigger chung: `CREATE TRIGGER <bảng>_lifecycle BEFORE INSERT OR UPDATE OR DELETE ON <bảng> FOR EACH ROW EXECUTE FUNCTION e9x_guard_version_lifecycle('updated_at')` (cột `status text` mang tên `ContentStatus`; đối số = cột được đổi cùng lúc với đổi trạng thái). Bản `IN_REVIEW` bị đóng băng, `PUBLISHED` chỉ sang `ARCHIVED`, không nhảy thẳng sang `PUBLISHED`; role ứng dụng chỉ INSERT được bản `DRAFT` (owner/Flyway và superuser thì không bị chặn). Không dùng cột stored generated trên bảng được bảo vệ (trong trigger BEFORE giá trị là NULL nên so sánh "không đổi gì khác" sai); bảng thiếu cột `status` bị từ chối ở mọi thao tác. Trạng thái của hàng nội dung không có liên kết CSDL tới `review_requests`: quy tắc "có review mới được PUBLISHED" nằm ở service.
> - Hợp đồng `ReviewableType`: RLS của bảng version phải cho REVIEWER của kỳ thi, trong ngữ cảnh của nội dung, vừa đọc vừa khoá (chính sách SELECT và UPDATE) hàng version, nếu không `lockForReview` trả rỗng. Phụ thuộc: thử `lockStatus` (FOR SHARE) rồi `readStatus` (SELECT thường). Phụ thuộc khác chủ (nội dung English9x đã xuất bản mà tổ chức dùng) chỉ cần PUBLISHED và không bị khoá; phụ thuộc không tồn tại hoặc không đọc được thì được tính là chưa xuất bản.
> - Sau `requestChanges` phiên bản ở `CHANGES_REQUESTED` (sửa được như DRAFT, đúng spec §7.1); gửi duyệt lại tạo review mới, review cũ giữ lịch sử. Điểm ghi nhật ký (P1.9) đã có chú thích `Audit (P1.9)` trong `ReviewServiceImpl`.
> - Kho chung (F21) CHƯA có trong workflow: xem ghi chú ở P6.5.

### Task P1.7: Hàng đợi nền (W1)

> **Ghi chú 2026-11-07 (từ P1.3):** quyết định của controller: ngữ cảnh worker là một login role riêng `e9x_worker_login` với pool nhỏ riêng, chỉ các handler của `platform.jobs` dùng, không bao giờ truy cập được từ HTTP, không có GUC `app.system`, quyền hạn hẹp nhất có thể. Việc không có ngữ cảnh người dùng khiến job thấy 0 dòng; không giải quyết bằng cờ.

**Files:** `backend/e9x-app/src/main/java/com/english9x/platform/jobs/**`, migration `V202611070002__jobs.sql`.

**Interfaces (Produces):** `JobQueue#enqueue(String type, JsonNode payload, Instant runAt)`; `@JobHandler("type")` bean `JobResult handle(Job)`; worker poll `FOR UPDATE SKIP LOCKED`, `e9x.grading.ai.retry-delays` dạng chung `e9x.jobs.<type>.retry-delays`; bật/tắt bằng `app.worker.enabled`.

**Tests:** `JobQueueIT.twoWorkersNeverTakeSameJob`, `.retriesWithConfiguredDelays`, `.marksFailedAfterLastRetry`, `.disabledWorkerDoesNotPoll`.

> **Ghi chú 2026-11-07 (P1.7 đã làm; việc cho P1.8, P3.3 và các handler sau):**
> - API công khai ở `com.english9x.platform.jobs.api` (named interface `api`): `JobQueue`, `Job`, `JobResult`, `@JobHandler("type")` + `JobExecutor#handle(Job)`, `WorkerJdbc`. `enqueue` **bắt buộc có transaction của người gọi** (job chỉ tồn tại nếu người gọi commit; không có transaction thì `IllegalTransactionStateException`); role `e9x_app` chỉ có quyền INSERT dòng QUEUED, không đọc được `jobs`.
> - Handler chạy trên luồng worker, **không có request, transaction hay ngữ cảnh người dùng**. Cần góc nhìn RLS của một người dùng (vd. job chấm bài thi, auto_submit_attempt): ghi `userId`/`orgId` vào payload lúc enqueue (mã server đáng tin cậy ghi), rồi trong handler `CurrentContextHolder.bind(userId, orgId)` và chạy `@Transactional` trên transaction manager chính (login `e9x_app_login`, RLS áp dụng như một request). Không có cờ hệ thống `app.system`. Muốn xem dữ liệu **không** theo người dùng (expire_orders, expire_entitlements, plan_expiry_reminder): migration của handler đó cấp quyền hẹp (GRANT + POLICY `TO e9x_worker`) và handler dùng `WorkerJdbc` (login `e9x_worker_login`, chỉ gọi được trong lúc chạy job). Mặc định worker chỉ thấy bảng `jobs`.
> - Job được chạy **ít nhất một lần**, handler phải idempotent. Trong lúc chạy, worker gia hạn lease bằng heartbeat (`e9x.jobs.heartbeat-interval`, mặc định lease/3), nên handler chậm mà còn sống không bị nhận lại; worker chết thì job nhận lại được sau lease (lần chạy mất vẫn tính là một lần thử). Heartbeat dừng sau `e9x.jobs.<type>.max-run` (mặc định 30m, WARN) nên job treo cuối cùng cũng nhận lại được: sau khi quá max-run hoặc mất heartbeat vẫn có thể có lần chạy song song thứ hai; kết quả muộn của lần cũ bị chặn bằng `lease_token`. Lỗi lưu `last_error` chỉ là tên lớp ngoại lệ (hoặc lý do handler tự đặt trong `JobResult.failure`, tối đa 500 ký tự, **không đưa nội dung payload vào**). Không log payload.
> - `JobExecutor#onExhausted(Job, reason)` (mặc định no-op) được gọi TRƯỚC khi job chuyển FAILED (hết thử lại hoặc lease hết sau lần thử cuối; không gọi khi loại job không có handler): nơi handler đặt trạng thái cuối (AI_FAILED, cảnh báo admin). At-least-once (crash giữa callback và FAILED thì chạy lại) nên phải idempotent; callback ném lỗi chỉ bị log (tên lớp). `enqueue` chỉ chấp nhận transaction của kết nối chính (transaction của worker bị từ chối); ghi qua `WorkerJdbc` và enqueue KHÔNG atomic với nhau (không có outbox): handler tự sắp thứ tự và idempotent. `JobExecutionListener` (api) là hook sau mỗi lần chạy handler; `platform.context.LeakedBindingCleaner` gỡ binding người dùng bị bỏ lại. Loại trong `e9x.jobs.priority-types` (mặc định `auto_submit_attempt`) có một luồng riêng; health `jobWorker` DOWN khi worker bật mà luồng đã mất. Instance `app.worker.enabled=false` vẫn khởi động được handler inject `WorkerJdbc` (bean từ chối khi dùng).
> - Độ trễ thử lại: `e9x.jobs.<type>.retry-delays`; `ai_grade` dùng `e9x.grading.ai.retry-delays` (§15.1, không nhân đôi giá trị); loại khác dùng `e9x.jobs.default-retry-delays`. Retry = số lần thử sau lần đầu; hết thì FAILED. Loại chưa có handler: FAILED ngay với "no handler registered".
> - Xếp hàng job định kỳ (expire_orders…) và dọn job cũ chưa làm: P1.7 chỉ có hàng đợi; worker hiện chỉ có SELECT/UPDATE (cột bookkeeping) trên `jobs`, chưa có INSERT/DELETE.

### Task P1.8: notification (W2) — F52, F53, F54, F55

> **Ghi chú 2026-11-07 (từ P1.4):** thư tài khoản (xác thực email, đã có tài khoản, đặt lại mật khẩu) hiện do `identity.internal.SmtpAuthMailer` gửi qua `spring-mail` (SMTP; Mailpit khi E2E), vì module notification chưa tồn tại. Có thể chuyển sang notification sau: thay `AuthMailer` bằng một `NotificationChannel` email, giữ nguyên nội dung và ba quy tắc (gửi sau commit, lỗi gửi không làm hỏng đăng ký, link dựng từ `e9x.public-base-url`, token không vào log). Thư bảo mật (đặt lại mật khẩu, xác thực) **không** được tắt theo tuỳ chọn thông báo của người dùng.

**Files:** `backend/e9x-app/src/main/java/com/english9x/notification/**`, migration `V202611080003__notifications.sql`, frontend `frontend/src/features/notifications/**`, E2E `e2e/specs/notify/F52…F55`.

**Interfaces (Produces):** `NotificationChannel { String id(); void send(Recipient, Message) }` với `InAppChannel`, `EmailChannel` (SMTP), `TelegramChannel` (Bot API, base URL cấu hình để trỏ fake). `NotificationService#notify(EventType, UUID userId, Map<String,Object> data)` tạo job `send_notification` cho mỗi kênh bật trong `notification_prefs` (in-app luôn bật). `POST /api/me/telegram/link` → `{deepLink}` với token một lần; webhook `POST /api/telegram/webhook` nhận `/start <token>`. Danh sách `EventType` theo bảng spec §13.

**Tests:** `NotificationServiceIT.respectsPrefsPerEventAndChannel`, `TelegramLinkIT.tokenSingleUse`, `EmailChannelIT.rendersVietnameseTemplate`. E2E F52–F55 (mailpit, fake-telegram).

> **Ghi chú 2026-11-08 (từ P1.6, nguồn thông báo duyệt, spec §13):** workflow phát `ReviewRequested` (cho REVIEWER của kỳ thi, trong ngữ cảnh của nội dung), `ReviewChangesRequested` và `ReviewApproved` (cho tác giả, có `authorId`); xem `events.md`. Từ ngữ cảnh một request không thể liệt kê REVIEWER vì RLS của `role_grants` chỉ cho thấy dòng của chính mình, nên P1.8 cần một hàm tra cứu `SECURITY DEFINER` hẹp để lấy người nhận (REVIEWER của kỳ thi, trong đúng ngữ cảnh của nội dung, đối số `org`/`exam` không cho dò người khác).

> **Ghi chú 2026-11-08 (P1.8 đã làm; việc cho mọi task phát thông báo, P1.9, P2.9, P5.x, P4.x, P6.x):**
> - Module `com.english9x.notification`: `api` (`NotificationService`, `EventType`, `Channel`, `NotificationChannel`, `Recipient`, `Message`) và `internal`. Gửi một thông báo: `notificationService.notify(EventType.X, userId, Map.of("examCode", "IELTS", ...), "<nguồn>:" + idTựNhiên)` từ **listener sự kiện** (khoá chống trùng bắt buộc, vì giao ít nhất một lần). Thêm một `EventType`: hằng số trong `EventType` + `NAME.title/body` ở `resources/notification/templates.{vi,en}.properties` + `event.NAME.title/body` ở `frontend/src/i18n/ns/notifications.{vi,en}.json` (`TemplatesTest` và test i18n bắt thiếu).
> - **Quyết định đường dữ liệu cho worker:** payload job chỉ có `{notificationId, channel}` (không email, tên, chat id hay nội dung: bảng `jobs` không giữ dữ liệu cá nhân). Worker KHÔNG đọc `users`/prefs/liên kết Telegram: hai hàm `SECURITY DEFINER` hẹp, chỉ `EXECUTE` cho `e9x_worker`, `notification_delivery_load(id, channel)` (chỉ trả địa chỉ hoặc chat id đúng kênh, chỉ khi delivery còn PENDING; đọc lại tuỳ chọn và liên kết ngay lúc gửi) và `notification_delivery_finish`. Thông báo trong app luôn được tạo cùng transaction với việc xếp job (`notification_create` idempotent theo `(user, dedupe_key)`); gửi lỗi thử lại theo `e9x.jobs.send_notification.retry-delays` rồi `onExhausted` ghi FAILED, dòng trong app còn nguyên.
> - **Người nhận REVIEWER:** `notification_reviewers_of_review(reviewId)` (`SECURITY DEFINER`, `EXECUTE` cho `e9x_app`): đối số là id của một review đang mở, không phải tổ chức hay kỳ thi, nên request không dùng nó để liệt kê người của tổ chức khác; trả REVIEWER của kỳ thi trong đúng ngữ cảnh của review, trừ tác giả và người gửi.
> - **Quy tắc liên kết Telegram:** token 256 bit, chỉ lưu SHA-256, một lần, hạn `e9x.notification.telegram.link-ttl`; một chat thuộc về một tài khoản: chat đã gắn với tài khoản KHÁC bị từ chối (`CHAT_TAKEN`, token chưa dùng), chủ chat phải huỷ ở ứng dụng trước; tài khoản liên kết chat mới thì thay chat cũ. Webhook công khai nhưng bắt buộc header `X-Telegram-Bot-Api-Secret-Token` (so sánh thời gian hằng), luôn trả 200 `{}` cho nội dung hợp lệ; lời bot nói ở chat giống nhau cho token sai/không có/đã dùng/hết hạn.
> - Thư bảo mật vẫn ở `identity.internal.SmtpAuthMailer` (không đổi, không phụ thuộc tuỳ chọn). Khoá React Query của thông báo dùng `publicKey('me', userId, ...)`: thông báo thuộc về người, không đổi theo ngữ cảnh tổ chức, nhưng khoá mang userId để người đăng nhập sau không thấy cache người trước.
> - **Còn chờ nguồn** (đã ghi ở task sở hữu): `ORDER_*`, `PLAN_*` (P5.1/P5.2), `SCORE_PRELIMINARY`, `AI_GRADING_FAILED` (P4.2), `SCORE_OFFICIAL`, `GRADING_*` (P4.3), `ASSIGNMENT_*` (P6.2), `SESSION_REMINDER` (P6.3). E2E F52–F55 dùng sự kiện workflow qua seed (`POST /api/test/seed/review-event`) thay cho thao tác CMS thật của P2.9: ghi ở `e2e/partial-features.json`.
> - Hạn chế đã chấp nhận (ruling vòng sửa 1: không làm definer theo nguồn vì `e9x_app` đã INSERT được `jobs` và `event_publication`, nên thu hẹp một đường không bỏ quyền; thay vào đó consumer đọc lại dòng nguồn, event là gợi ý): một câu lệnh bị chiếm quyền của role ứng dụng gọi được `notification_create` cho mọi user id nó biết (spam thông báo/email/Telegram, không đọc được gì của ai) và đọc id người duyệt của một review nó giữ id; chi tiết ở đầu migration. Gửi theo kiểu ít nhất một lần: worker sập giữa lúc gửi và lúc ghi SENT có thể gửi hai lần.

### Task P1.9: Nhật ký (W2) — F57

> **Ghi chú 2026-11-08 (P1.9 đã làm, xem `.superpowers/sdd/.../task-P1.9-report.md`):** (1) `audit.api.AuditLog#record(action, targetType, targetId, detail)` ghi **trong cùng giao dịch** với hành động (`MANDATORY`: không có giao dịch là lỗi); hành động rollback thì bản ghi mất theo, ghi lỗi thì hành động lỗi theo. Không best-effort. Lần bị từ chối (403/404/409/400) KHÔNG ghi. (2) Người thực hiện và ngữ cảnh KHÔNG phải tham số: `actor_id`, `org_id` lấy mặc định từ `e9x_current_user()` / `e9x_current_org()` của phiên (chủ sở hữu bản ghi = ngữ cảnh đã bind), `e9x_app` không có quyền INSERT hai cột đó và policy `WITH CHECK` buộc chúng bằng phiên. **Không có người dùng bind (job, listener không có người)** thì `record` ném `IllegalStateException` và CSDL cũng từ chối: muốn ghi việc của một người từ bước nền thì bind người đó trước (như listener thông báo); hệ thống tự nó không có actor trong v1. (3) Chỉ-thêm ở mức CSDL cho mọi người, kể cả `e9x_owner` và superuser: trigger từ chối UPDATE/DELETE (từng dòng) và TRUNCATE (từng câu lệnh), `ENABLE ALWAYS` (không tắt được bằng `session_replication_role`); `e9x_app` không có UPDATE/DELETE/TRUNCATE; không có khoá ngoại (nhật ký sống lâu hơn người và tổ chức). Chủ bảng vẫn có thể `DROP/ALTER` bảng (DDL, ví dụ `ALTER TABLE … DISABLE TRIGGER` rồi `DELETE`): không chặn được ở mức SQL, **không bị ghi lại mặc định** (không để dấu trong `flyway_schema_history`, và `log_statement` chưa bật), và tiến trình ứng dụng v1 đang giữ cả thông tin đăng nhập Flyway; tách thông tin Flyway khỏi tiến trình ứng dụng khi chạy thật nằm trong ghi chú ở P6.5. Gán actor chỉ vững tới mức mô hình bind phiên vững (câu lệnh của `e9x_app` tự `set_config('app.user_id', …)` được: giới hạn của P1.3). (4) Đọc: chỉ SYS_ADMIN thật (`e9x_is_sys_admin()`: cờ English9x VÀ quyền SYS_ADMIN), không có mệnh đề "mọi staff", **ORG_ADMIN không có màn nhật ký** (spec §14 đặt nhật ký ở quản trị hệ thống). API `GET /api/admin/audit-log` (lọc hành động, loại và mã đối tượng, người thực hiện = id hoặc email, khoảng ngày; phân trang theo `seq` giảm dần, `size` ≤ `e9x.audit.max-page-size`) và `GET /api/admin/audit-log/actions`. (5) `detail` chỉ chứa việc cần thiết; `AuditLog` từ chối khoá giống token/mật khẩu/bí mật/băm và khoá văn bản tự do (`comment`, `text`, `body`…), giá trị chuỗi dài hơn 500 ký tự và detail quá `e9x.audit.max-detail-bytes`. Nhận xét duyệt chỉ ghi độ dài. **Không ghi email của người được mời** (dữ liệu cá nhân của người có thể chưa bao giờ đăng ký; bảng chỉ-thêm không xoá được): `invitation.send` / `.revoke` / `.accept` ghi id lời mời làm đối tượng cùng `orgId` và vai trò, còn màn hình nhật ký tra địa chỉ từ bảng `invitations` lúc xem qua `target_id` (trường `invitedEmail`), nên xoá lời mời là xoá địa chỉ khỏi mọi nơi và dòng nhật ký hiện "Địa chỉ đã được xoá". Nhật ký chỉ giữ mã (`actor_id`, `target_id`, `org_id`); thủ tục xoá nằm ở P6.5. (6) Đã nối: `org.create`, `role.change` (chỉ khi vai trò đổi thật; `orgId` ở detail vì SYS_ADMIN hành động từ ngữ cảnh English9x), `invitation.send/revoke/accept` (chấp nhận do Java ghi, cùng giao dịch, actor = người nhận; hàm `org_invitation_accept` nay trả thêm `r_invitation_id` và `r_grants`), `review.submit/approve/request-changes` và `review.submit-bundle/approve-bundle/request-bundle-changes`, và `scripts/grant-sys-admin.sql` (`sys-admin.grant`, `actor_kind = OPERATOR`, không có actor). (7) **Chưa nối, thuộc chủ khác:** `org_join_code_redeem` (P6.1 gắn endpoint thì ghi `org.join-code-redeem`); **đổi loại tổ chức** (`organizations.type`) hiện chỉ có bằng SQL của SYS_ADMIN, ai thêm endpoint thì phải gọi `AuditLog#record("org.change-type", …)` trong cùng giao dịch; xác nhận/từ chối đơn (P5.2), gán/thu hồi suất (P5.3) và mọi thao tác quản trị của P5 (gói, tài khoản nhận tiền): xem ghi chú ở các task đó. (8) F57 là **PARTIAL** trong `e2e/partial-features.json`: thiếu hai bản ghi mà module chưa có (xác nhận đơn, thu hồi suất). Nội dung duyệt chạy qua `ReviewService` thật nhờ seed `review-flow` (nội dung giả `e2e_doc`) cho tới khi P2.9 có màn hình duyệt.

**Files:** `backend/e9x-app/src/main/java/com/english9x/platform/audit/**`, migration `V202611080005__audit_log.sql` (P1.8 đã dùng `V202611080003` và `V202611080004`; lấy số kế tiếp còn trống tại thời điểm làm, Flyway từ chối hai file cùng version), trang `frontend/src/features/admin/AuditLogPage.tsx`, E2E `e2e/specs/admin/F57-audit-log.spec.ts`.

**Interfaces:** `AuditLog#record(String action, String targetType, String targetId, Map<String,Object> detail)`; bảng chỉ INSERT (trigger chặn UPDATE/DELETE). Các module P2/P5/P6 gọi khi duyệt nội dung, xác nhận/từ chối đơn, gán/thu hồi suất, đổi quyền.

**Tests:** `AuditLogIT.appendOnly`, `.recordsActorAndContext`.

> **Ghi chú 2026-11-07 (từ P1.5, các điểm ghi nhật ký còn thiếu — P1.9 phải nối vào):** `AuditLog` chưa tồn tại nên P1.5 chỉ để chú thích `Audit (P1.9)` tại đúng chỗ trong mã. Các điểm cần ghi (actor = `CurrentContext#userId`, ngữ cảnh = `orgId`): (1) tạo tổ chức — `org.internal.OrganizationService#create` (id, tên, loại); (2) đổi vai trò — `org.internal.RoleGrantService#set` (tổ chức, người bị đổi, vai trò cũ → mới); (3) gửi và thu hồi lời mời — `org.internal.InvitationService#invite` / `#revoke` (tổ chức hoặc English9x, vai trò; KHÔNG ghi email, xem ghi chú ở P6.5); (4) chấp nhận lời mời — `InvitationService#accept` (người nhận, tổ chức, vai trò đã cấp; hàm `org_invitation_accept` chỉ trả `org_id`, nên đọc vai trò từ lời mời trước khi gọi hoặc mở rộng hàm trả về); (5) dùng mã tham gia — hàm `org_join_code_redeem` (P6.1 gắn endpoint thì ghi); (6) cấp SYS_ADMIN đầu tiên — `scripts/grant-sys-admin.sql` chạy bằng tay ngoài ứng dụng: P1.9 thêm một dòng `INSERT INTO audit_log` vào chính script đó; (7) đổi loại tổ chức (`organizations.type`) hiện chỉ làm được bằng SQL của SYS_ADMIN, chưa có endpoint: khi thêm endpoint phải ghi. Không ghi các endpoint seed `/api/test/seed/**`.

> **Ghi chú 2026-11-08 (từ P1.6, các điểm ghi nhật ký còn thiếu — P1.9 phải nối vào):** `ReviewServiceImpl` có chú thích `Audit (P1.9)` tại: gửi duyệt (`review.submit`, `review.submit-bundle`), duyệt (`review.approve`, `review.approve-bundle`) và yêu cầu sửa (`review.request-changes`, `review.request-bundle-changes`). Actor = người dùng trong ngữ cảnh, đích = id review hoặc cụm, chi tiết = loại, versionId, kỳ thi (không ghi nội dung nhận xét, chỉ độ dài). F57 cần các điểm này.

### Task P1.10: Frontend shell, design system, i18n, a11y (W1) — F58 (+ nền F07)

**Files:** `frontend/src/app/**` (router nạp `routes/<khu>/*.routes.ts` tự động, layout 4 khu, guard theo vai trò), `frontend/src/design/**` (token màu/khoảng cách/font sáng-tối, Button, Input, Select, Dialog, Tabs, Toast, Table, Badge, EmptyState, Timer, AudioPlayer, Recorder shell, QuestionNavigator, Highlighter), `frontend/src/i18n/{vi,en}.json`, `frontend/.storybook/**`, `docs/superpowers/design/mockups/*.html` (8 màn chính: danh mục, bản đồ chương, bài học, phòng thi Reading, phòng thi Speaking, kết quả, CMS duyệt, đơn QR quản trị), E2E `e2e/specs/common/F58-i18n-a11y.spec.ts`.

**Interfaces:** `useApi()` (client sinh từ OpenAPI, tự gắn `Authorization`, `X-Org-Context`); `<RequireRole role exam?>`; theme "Bàn học biên tập" (spec §14), font Be Vietnam Pro + Lora (self-host trong `frontend/public/fonts`).

**Tests:** Vitest cho từng component; Storybook test-runner + axe (không có vi phạm serious/critical); E2E F58 quét axe 8 màn chính và đổi ngôn ngữ.

> **Ghi chú 2026-11-07 (P1.10):** router dùng chung nằm ở `frontend/src/app/router.tsx` (không phải `frontend/src/router.tsx`); các task sau chỉ thêm `frontend/src/routes/<khu>/*.routes.ts` với `<khu>` ∈ `learner | cms | org | admin | exam` (xuất `defineRoutes([...])`, trang nên `lazy`). Chuỗi giao diện chung ở `frontend/src/i18n/{vi,en}.json`; task sau thêm chuỗi riêng bằng cặp `frontend/src/i18n/ns/<tính-năng>.vi.json` + `.en.json` (tự nạp thành namespace `<tính-năng>`), không sửa `vi.json`/`en.json` để tránh xung đột. Storybook axe chạy bằng `pnpm -C frontend run test:a11y` (cần Chromium của Playwright), là job CI riêng `frontend-a11y`; `verify.sh` chỉ chạy khi `E9X_A11Y=1`. Spec E2E F58 **chưa** có trong task này (xem ghi chú ở P1.12).

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

> **Ghi chú 2026-11-07 (từ P1.10):** `e2e/specs/common/F58-i18n-a11y.spec.ts` (quét axe 8 màn chính + đổi ngôn ngữ) được tạo cùng hạ tầng E2E này (P1.12), không nằm trong P1.10, vì 8 màn chính chưa tồn tại và gate E2E phải giữ trung thực. Spec bắt đầu với các màn đã có (trang chủ học viên, 4 khu ở trạng thái trống, tuỳ chọn hiển thị) và được mở rộng khi mỗi màn chính xuất hiện (P2/P3/P4); hoàn tất ở W10. Phần axe cho từng component đã chạy ở Storybook (`pnpm -C frontend run test:a11y`).

> **Ghi chú 2026-11-07 (từ vòng sửa P1.10):** P1.12 phụ trách SPA fallback của ứng dụng Spring: chuyển các GET deep link không phải API (ví dụ `/cms`, `/preferences`) về `index.html`, kèm một IT; `/api/**` và `/actuator/**` vẫn phải trả 404 thật. Hiện P1.10 chỉ chạy đúng khi điều hướng trong ứng dụng.

> **Ghi chú 2026-11-07 (P1.12 đã làm):** hạ tầng E2E chạy bằng `./scripts/e2e.sh` (Compose project `e9x-e2e`, DB sạch mỗi lần, luôn `down -v`; `--keep` để giữ stack, `--down` để dọn, tham số còn lại chuyển cho Playwright). Spec mới viết: `import { test, expect } from '../../fixtures'` (có `uniqueEmail`, `mailpit`, `telegram`, `gemini`, `seed`). Mỗi test cô lập dữ liệu bằng email/chatId duy nhất; fixture `gemini` khoá liên tiến trình và reset fake trước/sau test. Seed API: `POST /api/test/seed/*` chỉ tồn tại ở profile Spring `e2e` (IT `SeedDisabledIT` chứng minh 404 và không có bean khi thiếu profile), ghi bằng role chủ bảng `e9x_owner` qua `SeedDatabase` (không BYPASSRLS, không đổi policy). **Task sau thêm seed của mình:** (1) thêm endpoint `@Profile("e2e")` dưới `/api/test/seed/` trong `backend/e9x-app/src/main/java/com/english9x/platform/e2e/`, insert qua `SeedDatabase`; (2) thay `notImplemented(...)` tương ứng trong `e2e/fixtures/{seed,auth,org,content,billing}.ts` bằng lời gọi thật (chữ ký đã cố định theo brief; hàm chưa dựng sẽ ném `NotImplementedError` nêu task nào phải làm, không dùng fixture giả); (3) thêm spec `e2e/specs/<khu>/F<nn>-*.spec.ts`. Spec `_*.spec.ts` là spec hạ tầng, không tính là tính năng. SPA fallback đã làm (`SpaFallbackConfiguration` + `SpaFallbackIT`).
>
> **F58 CHƯA hoàn tất dù gate báo "covered" (1/58):** `e2e/specs/common/F58-i18n-a11y.spec.ts` quét axe (light/dark, vi/en) và đổi ngôn ngữ thật trên các màn đã có: trang chủ học viên, tuỳ chọn, khu phòng thi (trạng thái trống), màn "không đủ quyền" của `/cms` `/org` `/admin`, không tìm thấy. Trạng thái trống thật của cms/org/admin cần đăng nhập (P1.4/P1.5). **8 màn chính còn thiếu, phải chuyển từ `PENDING_MAIN_SCREENS` sang `SCREENS` trong spec:** 01 danh mục khoá (P3.1), 02 bản đồ chương (P3.1), 03 bài học (P3.2), 04 phòng thi Reading (P3.4), 05 Speaking (P3.4), 06 kết quả (P4.4), 07 CMS duyệt (P2.9), 08 đơn QR quản trị (P5.4). **W10 chỉ được coi là hoàn tất khi danh sách chờ này rỗng** (mỗi lần chạy spec in danh sách chờ làm annotation). CI: job `e2e` chưa chặn merge (`continue-on-error`) cho tới W10; khi bật `E9X_E2E_GATE=strict` thì gỡ cờ này.
