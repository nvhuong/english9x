# P5 — Thương mại: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gói B2C linh hoạt, đơn VietQR xác nhận thủ công, quyền học theo entitlement, gói suất tổ chức kích hoạt bằng QR hoặc hợp đồng, gán/thu hồi suất.

**Architecture:** `billing` bọc `Plan`, `PlanSelection`, `Order`, `TransferNote`, `VietQr`, `Entitlements`, `AccessPolicy`, `SeatPool` của `e9x-domain` (đã làm ở wave cục bộ L1b). Ảnh QR sinh bằng ZXing trên server. Mọi thao tác quản trị ghi `AuditLog` (P1.9).

**Tech Stack:** Spring Boot, JPA, ZXing; React.

**Spec:** §11, §12 (gói suất, gán suất), §15.1 (`e9x.billing.*`). Global Constraints + Review Focus #3, #5: overview.

---

### Task P5.1: gói + đơn + quyền học (W3) — F39, F41

**Files:** `backend/e9x-app/src/main/java/com/english9x/billing/{plan,order,entitlement}/**`, migration `V202611100004__billing_plans_orders.sql` (`plans`, `orders`, `order_items`; dùng `entitlements` của L1d), job `expire_orders`, `expire_entitlements` (đánh dấu hết hạn, thông báo), `plan_expiry_reminder`, E2E `F39-b2c-plan.spec.ts`, `F41-entitlement-access.spec.ts`.

**Interfaces (Produces):**
- `GET /api/plans` (đang bán); admin `POST/PUT /api/admin/plans {name, priceVnd, months, maxCourses, active}`.
- `POST /api/orders {planId, courseIds}` → `PlanSelection.validate`, chỉ khoá PUBLISHED trong danh mục, giá chốt tại thời điểm đặt, `orderCode` 6 ký tự (`Codes.isValidOrderCode`, sinh ngẫu nhiên không trùng), yêu cầu email đã xác thực (P1.4) → 201 `{orderCode, amountVnd, transferNote, qrPayload, qrPngUrl}`.
- `billing.api.Entitlements#activeFor(userId, courseId, now)`; `#canContinueAttempt(userId, courseId, attemptStartedAt)` (Review Focus #5) — dùng bởi `course.api.LessonGate` (P3.1) và `exam` (P3.3).
- Job `expire_orders` dùng `e9x.billing.order-expiry`; `plan_expiry_reminder` dùng `e9x.billing.expiry-reminder`.

**Tests:** `OrderServiceIT.rejectsMoreCoursesThanPlan`, `.unverifiedEmailCannotOrder`, `.priceLockedAtOrderTime`, `.orderCodesUnique`, `EntitlementGateIT.expiredDeniesNewLessonButAllowsHistory`, `.attemptInProgressSurvivesExpiry`, `ExpireOrdersJobIT.pendingOrderExpiresAfterConfiguredTtl`.

> **Ghi chú 2026-11-07 (từ P1.4):** `OrderService` (POST /api/orders) **phải gọi `identity.api.UserDirectory#requireVerified(userId)`** trước khi tạo đơn; khi chưa xác thực email nó ném `EmailNotVerifiedException` (403 `urn:e9x:problem:email-not-verified`). P1.4 chỉ kiểm thử được cái guard (`RegistrationIT.unverifiedUserCannotCheckout`) vì chưa có thanh toán. **Việc còn lại của P5 (đánh dấu pending như F58):** (a) `OrderServiceIT.unverifiedEmailCannotOrder` dùng đúng guard này; (b) kịch bản F01 "chưa xác thực thì không mua được gói" qua giao diện thanh toán thật phải được thêm vào `e2e/specs/auth/F01-register-verify.spec.ts` (hiện spec chỉ khẳng định guard qua seed API `/api/test/seed/require-verified` và biểu ngữ "email chưa xác thực"; đầu spec có ghi chú "WHAT THIS SPEC CANNOT DO YET"); (c) thay `seed.learnerWithPlan` (còn `NotImplementedError`) bằng seed thật.

> **Ghi chú 2026-11-07 (từ P1.7, vòng sửa 1):** P5.1 sở hữu việc lên lịch định kỳ (job lặp) đầu tiên: `expire_orders`, `expire_entitlements`, `plan_expiry_reminder`, `release_expired_claim` (P1.7 chỉ có hàng đợi; worker hiện không có INSERT/DELETE trên `jobs`). Cần: cột/khoá `dedupe_key` để hai instance không xếp trùng cùng một lần chạy, chính sách giữ/dọn payload và job đã xong (payload có thể chứa dữ liệu người dùng), và migration cấp quyền hẹp cho `e9x_worker` (GRANT + POLICY) cho phần dữ liệu các handler này đụng tới.

> **Ghi chú 2026-11-07 (từ P1.4, vòng sửa 2):** thêm vào danh sách job định kỳ của P5.1: `purge_no_consent_accounts`, gọi `identity.api.AccountHousekeeping#purgeAccountsWithoutConsent` (hàm `auth_purge_no_consent`, tuổi giữ `e9x.auth.no-consent-retention`, mặc định 7d). Hiện chưa có gì chạy nó theo lịch: hàng đợi chưa có đường xếp job lặp nên P1.4 chỉ mở hàm. Handler chạy bằng role nào phải được chốt khi làm: EXECUTE hiện chỉ cấp cho `e9x_app`, `e9x_worker` chưa có. Tần suất gợi ý: một lần mỗi ngày.

### Task P5.2: VietQR + xác nhận thủ công (W4) — F40

**Files:** `backend/e9x-app/src/main/java/com/english9x/billing/payment/**` (`PaymentAccountService`, `QrImageController`, `OrderAdminController`), migration `V202611110003__payment_accounts.sql`, `frontend/src/features/billing/{CheckoutPage,OrderStatusPage}.tsx`, `frontend/src/features/admin/{PendingOrdersPage,PaymentAccountPage}.tsx`, E2E `F40-vietqr-order.spec.ts`.

**Interfaces:**
- Admin `PUT /api/admin/payment-account {bankBin, accountNo, accountName}` (một tài khoản hoạt động).
- `qrPayload` = `VietQr.payload(new VietQrRequest(bin, accountNo, amount, TransferNote.build(orderCode, courseCodes, e9x.billing.transfer-note-max-length)))`; `GET /api/orders/{code}/qr.png` (ZXing, 512 px, mức sửa lỗi M).
- `GET /api/admin/orders?status=PENDING_PAYMENT&q=`; `POST /api/admin/orders/{code}/confirm {bankRef}` → `Order.confirm` + `Entitlements.forConfirmedOrder` + `OrderConfirmed` + thông báo + audit; `POST /api/admin/orders/{code}/reject {reason}` → `OrderRejected` + thông báo + audit.
- Thông báo quản trị khi có đơn mới (app/email/Telegram theo prefs).

**Tests:** `QrPayloadIT.decodesToConfiguredAccountAmountAndNote` (giải mã ảnh PNG bằng ZXing → `VietQr.parseTopLevel`), `.longCourseListFallsBackToOrderCode`, `OrderAdminIT.confirmCreatesEntitlementsFromConfirmationTime`, `.rejectRequiresReason`, `.confirmTwiceConflict`, `.auditRecorded`. Trước khi phát hành: kiểm thủ công quét QR bằng 3 app ngân hàng (ghi kết quả vào PR) — rủi ro độ dài trường `62.08` khác nhau giữa ngân hàng.

### Task P5.3: gói suất tổ chức + gán suất (W5) — F42, F43

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
