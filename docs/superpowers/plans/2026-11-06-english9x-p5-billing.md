# P5 — Thương mại: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gói B2C linh hoạt, đơn VietQR xác nhận thủ công, quyền học theo entitlement, gói suất tổ chức kích hoạt bằng QR hoặc hợp đồng, gán/thu hồi suất.

**Architecture:** `billing` bọc `Plan`, `PlanSelection`, `Order`, `TransferNote`, `VietQr`, `Entitlements`, `AccessPolicy`, `SeatPool` của `e9x-domain` (đã làm ở wave cục bộ L1b). Ảnh QR sinh bằng ZXing trên server. Mọi thao tác quản trị ghi `AuditLog` (P1.9).

**Tech Stack:** Spring Boot, JPA, ZXing; React.

**Spec:** §11, §12 (gói suất, gán suất), §15.1 (`e9x.billing.*`). Global Constraints + Review Focus #3, #5: overview.

---

### Task P5.1: gói + đơn + quyền học (W3) — F39, F41

> **Ghi chú 2026-11-08 (từ P1.8, quyết định về quyền của role request):** event là **gợi ý**, không phải bằng chứng. Role `e9x_app` có INSERT/UPDATE/DELETE `event_publication` và INSERT `jobs` (thư viện Modulith buộc phải cấp), nên một câu lệnh SQL bị chiếm quyền dựng được event giả hợp lệ hình dạng. Listener của task này phải **đọc lại dòng nguồn** (lượt thi, đơn, suất ...) và kiểm tra khớp với event trước khi làm việc quan trọng; không tin id/giá trị mang trong event. Xem `docs/superpowers/design/events.md`.

> **Ghi chú 2026-11-08 (từ P1.8):** Nguồn thông báo của task này (spec §13), do P1.8 khai báo `EventType` + mẫu vi/en nhưng CHƯA có nguồn (không dựng nguồn giả): `PLAN_EXPIRING` (còn `e9x.billing.expiry-reminder` = 7 ngày) và `PLAN_EXPIRED` cho học viên, phát từ job định kỳ `plan_expiry_reminder`/`expire_entitlements` bằng `NotificationService.notify(EventType.PLAN_EXPIRING, userId, data, "plan-expiring:" + entitlementId)` (khoá chống trùng theo entitlement, job chạy lại không báo hai lần). Worker không đọc được `users`: job chỉ cần userId trong payload và gọi `notify` (qua `JobQueue`/transaction chính). Dữ liệu `data` chỉ là id/mã, không tiêu đề khoá.


**Files:** `backend/e9x-app/src/main/java/com/english9x/billing/{plan,order,entitlement}/**`, migration `V202611100004__billing_plans_orders.sql` (`plans`, `orders`, `order_items`; dùng `entitlements` của L1d), job `expire_orders`, `expire_entitlements` (đánh dấu hết hạn, thông báo), `plan_expiry_reminder`, E2E `F39-b2c-plan.spec.ts`, `F41-entitlement-access.spec.ts`.

**Interfaces (Produces):**
- `GET /api/plans` (đang bán); admin `POST/PUT /api/admin/plans {name, priceVnd, months, maxCourses, active}`.
- `POST /api/orders {planId, courseIds}` → `PlanSelection.validate`, chỉ khoá PUBLISHED trong danh mục, giá chốt tại thời điểm đặt, `orderCode` 6 ký tự (`Codes.isValidOrderCode`, sinh ngẫu nhiên không trùng), yêu cầu email đã xác thực (P1.4) → 201 `{orderCode, amountVnd, transferNote, qrPayload, qrPngUrl}`.
- `billing.api.Entitlements#activeFor(userId, courseId, now)`; `#canContinueAttempt(userId, courseId, attemptStartedAt)` (Review Focus #5) — dùng bởi `course.api.LessonGate` (P3.1) và `exam` (P3.3).
- Job `expire_orders` dùng `e9x.billing.order-expiry`; `plan_expiry_reminder` dùng `e9x.billing.expiry-reminder`.

**Tests:** `OrderServiceIT.rejectsMoreCoursesThanPlan`, `.unverifiedEmailCannotOrder`, `.priceLockedAtOrderTime`, `.orderCodesUnique`, `EntitlementGateIT.expiredDeniesNewLessonButAllowsHistory`, `.attemptInProgressSurvivesExpiry`, `ExpireOrdersJobIT.pendingOrderExpiresAfterConfiguredTtl`.

> **Ghi chú 2026-11-07 (từ P1.4):** `OrderService` (POST /api/orders) **phải gọi `identity.api.UserDirectory#requireVerified(userId)`** trước khi tạo đơn; khi chưa xác thực email nó ném `EmailNotVerifiedException` (403 `urn:e9x:problem:email-not-verified`). P1.4 chỉ kiểm thử được cái guard (`RegistrationIT.unverifiedUserCannotCheckout`) vì chưa có thanh toán. **Việc còn lại của P5 (đánh dấu pending như F58):** (a) `OrderServiceIT.unverifiedEmailCannotOrder` dùng đúng guard này; (b) kịch bản F01 "chưa xác thực thì không mua được gói" qua giao diện thanh toán thật phải được thêm vào `e2e/specs/auth/F01-register-verify.spec.ts` (hiện spec chỉ khẳng định guard qua seed API `/api/test/seed/require-verified` và biểu ngữ "email chưa xác thực"; đầu spec có ghi chú "WHAT THIS SPEC CANNOT DO YET"); (c) thay `seed.learnerWithPlan` (còn `NotImplementedError`) bằng seed thật.

> **Ghi chú 2026-11-07 (từ P1.7, vòng sửa 1):** P5.1 sở hữu việc lên lịch định kỳ (job lặp) đầu tiên: `expire_orders`, `expire_entitlements`, `plan_expiry_reminder`, `release_expired_claim` (P1.7 chỉ có hàng đợi; worker hiện không có INSERT/DELETE trên `jobs`). Cần: cột/khoá `dedupe_key` để hai instance không xếp trùng cùng một lần chạy, chính sách giữ/dọn payload và job đã xong (payload có thể chứa dữ liệu người dùng), và migration cấp quyền hẹp cho `e9x_worker` (GRANT + POLICY) cho phần dữ liệu các handler này đụng tới.

> **Ghi chú 2026-11-08 (review cuối P1, sửa plan 9, M10, phán quyết):** **chuyển trạng thái của hệ thống có ghi audit** (spec §11: mọi thao tác ghi `audit_log`), ví dụ `expire_orders`. Hôm nay không ghi được: policy đòi USER, worker không có grant, `record` ném lỗi khi không có binding. Task này thêm actor kind `SYSTEM` (CHECK và màn hình `/admin/audit-log`) và **một đường cho worker**, definer hẹp, không có người dùng nào đứng tên.

> **Ghi chú 2026-11-10 (kết quả P5.1, cho P5.2/P5.3/P5.4):** migration thật là `V202611110001__billing_plans_orders.sql` (+ `V202611110002__recurring_jobs_system_audit.sql`), không phải `V202611100004`. P5.2: xác nhận đơn chạy trong transaction của quản trị: cập nhật `orders` (cần thêm policy UPDATE cho SYS_ADMIN, hiện chỉ có SELECT/INSERT), tạo `entitlements` (policy `entitlements_insert_order` đã có) với hạn `Entitlements.forConfirmedOrder` dùng `orders.plan_months` đã khoá, `AuditLog#record`, phát `OrderConfirmed`; `ORDER_PENDING` (báo quản trị khi có đơn mới) CHƯA có nguồn, P5.2 phát từ `OrderService#create` hoặc listener; `qrPayload`/`qrPngUrl` của `POST /api/orders` đang `null`, P5.2 điền. P5.3: chưa có việc nhắc hạn cho entitlement suất (`billing_entitlements_due` chỉ lấy `source_order_id IS NOT NULL`). P4.x: `release_expired_claim` CHƯA làm vì chưa có bảng claim; dùng `RecurringJob` + `@JobHandler` và thêm hành động vào `audit_log_record_system` nếu hệ thống tự đổi trạng thái.

### Task P5.2: VietQR + xác nhận thủ công (W4) — F40

> **Ghi chú 2026-11-08 (từ P1.9, F57 cần bản ghi này):** gọi `audit.api.AuditLog#record` trong CÙNG giao dịch với việc xác nhận / từ chối đơn (spec §6: mọi thao tác quản trị đều ghi `audit_log`): `order.confirm` và `order.reject`, `targetType` `order`, `targetId` = id đơn, detail chỉ gồm mã gói, số tiền, mã giao dịch ngân hàng nếu có, lý do từ chối dưới dạng độ dài (không ghi văn bản tự do, không ghi số tài khoản). Actor và ngữ cảnh tự lấy từ phiên. Mỗi thao tác quản trị khác của P5 (gói, tài khoản nhận tiền) cũng ghi. Rồi mở rộng `e2e/specs/admin/F57-audit-log.spec.ts` bằng dòng "xác nhận đơn" và bỏ phần tương ứng trong mục F57 của `e2e/partial-features.json`.

> **Ghi chú 2026-11-08 (từ P1.8):** Nguồn thông báo của task này (spec §13), do P1.8 khai báo `EventType` + mẫu vi/en nhưng CHƯA có nguồn (không dựng nguồn giả): `ORDER_PENDING` (cho mọi SYS_ADMIN: cần hàm tra cứu `SECURITY DEFINER` hẹp để liệt kê SYS_ADMIN, giống `notification_reviewers_of_review`, không cấp đọc `role_grants` rộng), `ORDER_CONFIRMED` (từ `OrderConfirmed`, người nhận `userId` của đơn; đơn của tổ chức thì ORG_ADMIN), `ORDER_REJECTED` (từ `OrderRejected`; `data.reason` là chữ do quản trị nhập, được escape theo kênh nhưng đừng đưa chuỗi dài > 500 ký tự). Nối bằng `@ApplicationModuleListener` trong `notification.internal` (theo mẫu `ReviewNotifications`) hoặc gọi `NotificationService.notify` từ listener của billing; E2E F52–F55 đang ghi `partial-features.json` chờ nguồn này.


**Files:** `backend/e9x-app/src/main/java/com/english9x/billing/payment/**` (`PaymentAccountService`, `QrImageController`, `OrderAdminController`), migration `V202611110003__payment_accounts.sql`, `frontend/src/features/billing/{CheckoutPage,OrderStatusPage}.tsx`, `frontend/src/features/admin/{PendingOrdersPage,PaymentAccountPage}.tsx`, E2E `F40-vietqr-order.spec.ts`.

**Interfaces:**
- Admin `PUT /api/admin/payment-account {bankBin, accountNo, accountName}` (một tài khoản hoạt động).
- `qrPayload` = `VietQr.payload(new VietQrRequest(bin, accountNo, amount, TransferNote.build(orderCode, courseCodes, e9x.billing.transfer-note-max-length)))`; `GET /api/orders/{code}/qr.png` (ZXing, 512 px, mức sửa lỗi M).
- `GET /api/admin/orders?status=PENDING_PAYMENT&q=`; `POST /api/admin/orders/{code}/confirm {bankRef}` → `Order.confirm` + `Entitlements.forConfirmedOrder` + `OrderConfirmed` + thông báo + audit; `POST /api/admin/orders/{code}/reject {reason}` → `OrderRejected` + thông báo + audit.
- Thông báo quản trị khi có đơn mới (app/email/Telegram theo prefs).

**Tests:** `QrPayloadIT.decodesToConfiguredAccountAmountAndNote` (giải mã ảnh PNG bằng ZXing → `VietQr.parseTopLevel`), `.longCourseListFallsBackToOrderCode`, `OrderAdminIT.confirmCreatesEntitlementsFromConfirmationTime`, `.rejectRequiresReason`, `.confirmTwiceConflict`, `.auditRecorded`. Trước khi phát hành: kiểm thủ công quét QR bằng 3 app ngân hàng (ghi kết quả vào PR) — rủi ro độ dài trường `62.08` khác nhau giữa ngân hàng.

### Task P5.3: gói suất tổ chức + gán suất (W5) — F42, F43

> **Ghi chú 2026-11-08 (từ P1.9, F57 cần bản ghi này):** gán và **thu hồi suất** gọi `audit.api.AuditLog#record` trong cùng giao dịch: `seat.assign`, `seat.revoke` (và `license.create`/`license.change` cho giấy phép suất), `targetType` `seat` hoặc `license`, `targetId` = id suất / giấy phép, detail gồm id người dùng và id khoá, không ghi email. Mở rộng `F57-audit-log.spec.ts` bằng dòng "thu hồi suất" và bỏ phần tương ứng trong mục F57 của `e2e/partial-features.json`.

> **Ghi chú 2026-11-07 (từ P1.3):** quyền học theo suất cần FK tới `seat_assignments`, kiểm tra khoá thuộc `org_license_courses` của giấy phép và kiểm tra sức chứa (số suất). Hiện ORG_ADMIN có thể chèn dòng suất tuỳ ý (`source_seat_id` chưa có FK, cột `entitlements.source_org_id` đã có và RLS buộc bằng tổ chức hiện tại) cho thành viên tổ chức mình; DB chưa chặn được vượt sức chứa. Thu hồi = UPDATE `valid_until`.

**Files:** `backend/e9x-app/src/main/java/com/english9x/billing/license/**`, migration `V202611120003__org_licenses.sql` (`org_licenses`, `org_license_courses`, `seat_assignments`), `frontend/src/features/org/licenses/**`, `frontend/src/features/admin/OrgLicensesPage.tsx`, E2E `F42-org-license.spec.ts`, `F43-seat-assign-revoke.spec.ts`.

**Interfaces:**
- Admin `POST /api/admin/org-licenses {orgId, seats, months, courseIds, priceVnd, activation: QR|CONTRACT, contractNo?}`; `CONTRACT` → kích hoạt ngay (audit); `QR` → tạo đơn như P5.2 (người trả là ORG_ADMIN).
- ORG_ADMIN `POST /api/orgs/{orgId}/licenses/{id}/seats {userId, courseIds ⊆ license.courseIds}` → `SeatPool.assign` + entitlement nguồn SEAT, hạn = hạn license; `DELETE …/seats/{userId}` → `SeatPool.revoke`, kết thúc entitlement ngay (`validUntil = now`), phát `SeatRevoked`.
- Khoá lạc quan trên license khi gán đồng thời.

**Tests:** `LicenseIT.contractActivationImmediate`, `.qrActivationAfterConfirm`, `SeatIT.cannotExceedSeats` (2 request đồng thời cho suất cuối → một thành công), `.revokeEndsAccessAndFreesSeat`, `.coursesOutsideLicenseRejected`, `.memberOfOtherOrgRejected`.

### Task P5.4: UI gói, đơn, quản trị (W8) — F39, F40 (UI)

**Files:** `frontend/src/features/billing/{PlanPicker,CoursePicker,MyOrdersPage}.tsx`, hoàn thiện trang quản trị đơn và tài khoản nhận tiền; E2E F39, F40 hoàn chỉnh (giải mã QR trên trang bằng `jsQR` trong test).

**Tests:** Vitest `CoursePicker.disablesBeyondMax`, `CheckoutPage.copyButtonsCopyExactNote`; E2E F39, F40.
