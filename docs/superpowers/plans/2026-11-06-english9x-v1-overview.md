# English9x v1 — Tổng quan Implementation Plan (P1–P6)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Xây English9x v1 (IELTS AC/GT + TOEIC L&R, B2C + B2B) đúng spec, mọi tính năng F01–F58 có kiểm thử E2E.

**Architecture:** Modular monolith Spring Boot 3 / Spring Modulith; lõi nghiệp vụ thuần Java trong module `e9x-domain` (không phụ thuộc framework); PostgreSQL 16 với Row-Level Security; React + TypeScript; Docker Compose trên một VPS.

**Tech Stack:** Java 21, Maven, Spring Boot 3.x, Spring Modulith, Flyway, PostgreSQL 16, MinIO, React 18, Vite, TanStack Query, Tailwind + Radix, Playwright, Testcontainers, k6.

**Spec:** `docs/superpowers/specs/2026-11-06-english9x-design.md`

**Các file plan**
- `2026-11-06-english9x-p1-foundation.md` — P1 Nền móng
- `2026-11-06-english9x-p2-content.md` — P2 Nội dung & CMS
- `2026-11-06-english9x-p3-learning-exam.md` — P3 Học & thi
- `2026-11-06-english9x-p4-grading-analytics.md` — P4 Chấm & phân tích
- `2026-11-06-english9x-p5-billing.md` — P5 Thương mại
- `2026-11-06-english9x-p6-b2b.md` — P6 B2B vận hành
- `2026-11-06-english9x-local-wave.md` — Wave cục bộ đang chạy được trong môi trường không mạng (chi tiết TDD đầy đủ)

## Global Constraints

- Java 21; Spring Boot 3.x; PostgreSQL 16; Node 22; React 18 + TypeScript strict.
- Package gốc `com.english9x`; module Modulith: `identity, org, catalog, content, course, workflow, learning, exam, grading, analytics, classroom, billing, notification`. Module chỉ gọi nhau qua gói `api` hoặc domain event.
- Logic nghiệp vụ thuần (chấm, quy đổi, VietQR, luật mở khoá, quyền học, chuỗi ngày, vòng đời lượt thi) nằm trong `backend/e9x-domain`, **không import Spring/JPA**; module Spring gọi vào đó.
- Test của `e9x-domain` chỉ dùng tập con JUnit 5: `@Test`, `Assertions.assertEquals/assertTrue/assertFalse/assertThrows/assertNull/assertNotNull` (để chạy được cả bằng JUnit thật lẫn shim offline `tools/offline-junit`).
- Migration Flyway: `backend/e9x-app/src/main/resources/db/migration/V<yyyymmddhhmm>__<module>_<mô tả>.sql`; mọi bảng dữ liệu riêng có `owner_org_id uuid NULL` + RLS; ứng dụng kết nối bằng role `e9x_app` (không phải chủ bảng).
- `answer_key` không bao giờ nằm trong DTO phòng thi trước khi nộp.
- Mọi thông số tự đặt lấy từ khoá cấu hình ở spec §15.1; **không** có giới hạn cứng lượt chấm AI (`e9x.grading.ai.per-user-daily-limit=0` = không giới hạn).
- Tiền: `long` đơn vị VND; thời gian: `Instant` UTC, chuỗi ngày học tính theo `ZoneId` của người dùng.
- Mỗi tính năng F01–F58 có ít nhất một spec Playwright tại `e2e/specs/<khu>/F<nn>-*.spec.ts`; `scripts/check-e2e-coverage.mjs` fail khi thiếu.
- Commit message theo Conventional Commits; kết thúc bằng dòng attribution của phiên.
- Không merge vào `main`; nhánh tích hợp `release/v1`.

## Review Focus

1. **Rò rỉ dữ liệu giữa tổ chức** khi người dùng thuộc 2 tổ chức đổi ngữ cảnh giữa chừng (request đang chạy, cache TanStack Query). Mong đợi: không hiển thị dữ liệu tổ chức cũ. Test: P1.5 `ContextSwitchIsolationIT` + E2E F07, F08.
2. **Nộp bài đúng lúc hết giờ** (người dùng bấm nộp cùng lúc job tự nộp). Mong đợi: một kết quả, đáp án cuối cùng đã lưu được tính. Test: P3.3 `ConcurrentSubmitIT`; domain `AttemptLifecycleTest.submitIsIdempotent`.
3. **Nội dung chuyển khoản có ký tự tiếng Việt / tên khoá dài** làm QR sai hoặc vượt 50 ký tự. Mong đợi: bỏ dấu, in hoa, rút về mã đơn khi vượt. Test: local wave `TransferNoteTest`.
4. **Đáp án điền từ có biến thể hợp lệ** (US/UK spelling, số viết bằng chữ, mạo từ) bị chấm sai. Mong đợi: chấp nhận biến thể khai báo, chuẩn hoá khoảng trắng/hoa thường. Test: local wave `GapTypedScorerTest`.
5. **Gói hết hạn giữa lúc đang thi**. Mong đợi: lượt thi đang làm được hoàn thành và chấm; chỉ chặn bắt đầu lượt mới. Test: domain `AccessPolicyTest.attemptInProgressSurvivesExpiry`; E2E F41.

---

## Chu trình chuẩn cho mỗi task (áp dụng cho mọi plan)

- [ ] Tạo worktree: `git worktree add ../wt-<task> -b <task-branch> release/v1`
- [ ] Viết test thất bại (unit/integration theo mục **Tests** của task)
- [ ] Chạy test, xác nhận FAIL đúng lý do
- [ ] Viết code tối thiểu theo **Interfaces**
- [ ] Chạy test, xác nhận PASS
- [ ] Viết/hoàn thiện spec E2E của các F-ID gắn với task (khi task có UI + API)
- [ ] Chạy `./scripts/verify.sh` (build + unit + IT + lint + gate E2E coverage)
- [ ] Commit; reviewer độc lập (superpowers:requesting-code-review); sửa theo review
- [ ] Merge vào `release/v1`, chạy lại toàn bộ test, `git worktree remove`

## Wave toàn dự án (tối đa 4 task song song)

| Wave | Task song song | Điều kiện vào |
|---|---|---|
| W0 | P1.1 Monorepo/CI/Compose · P1.2 Domain module + harness offline | — |
| W1 | P1.3 DB baseline + RLS · P1.4 identity · P1.7 Hàng đợi jobs · P1.10 Frontend shell/design system · P1.11 OpenAPI + lỗi RFC7807 + event contracts · P1.12 E2E harness + gate | W0 |
| W2 | P1.5 org + ngữ cảnh · P1.6 workflow · P1.8 notification · P1.9 audit | P1.3, P1.4, P1.7 |
| W3 | P2.1 catalog · P2.2 media · P5.1 gói + đơn (domain/API) | W2 |
| W4 | P2.3 câu hỏi/nhóm · P2.4 bài phương pháp · P2.5 bảng quy đổi + skill chấm · P5.2 VietQR + xác nhận | W3 |
| W5 | P2.6 dựng đề · P2.7 import Excel · P2.8 CMS UI · P5.3 gói suất + gán suất | W4 |
| W6 | P2.9 UI duyệt + so sánh phiên bản · P3.1 khoá học + danh mục + mở khoá · P3.3 exam backend · P4.1 chấm tự động + quy đổi | P2.6, P2.5 |
| W7 | P3.2 learning · P3.4 phòng thi UI · P6.1 thành viên + mã tham gia · P4.2 chấm AI (Gemini + fake) | W6 |
| W8 | P4.3 hàng chờ GV + chế độ Cả hai · P6.2 lớp + giao bài · P6.3 lịch + điểm danh · P5.4 UI gói/đơn/admin | W7 |
| W9 | P4.4 kết quả + điểm yếu + gợi ý · P4.5 tiến trình theo gói + giám sát AI · P6.4 báo cáo + Excel · P6.5 cấu hình chấm trung tâm + nội dung riêng + kho chung | W8 |
| W10 | Hoàn tất E2E F01–F58 · k6 50 người · review toàn nhánh · PR `release/v1 → main` | W9 |

Các task trong một wave không sửa chung file. File dùng chung (`pom.xml` gốc, `frontend/src/router.tsx`, `e2e/features.json`) chỉ sửa ở P1.1/P1.10/P1.12; task sau thêm route/feature bằng file đăng ký riêng (`frontend/src/routes/<khu>/*.routes.ts` được nạp tự động; `e2e/specs/**`).

## Ma trận F01–F58 → task → spec E2E

| F | Task chính | Spec E2E |
|---|---|---|
| F01 | P1.4 | `e2e/specs/auth/F01-register-verify.spec.ts` |
| F02 | P1.4 | `e2e/specs/auth/F02-google-login.spec.ts` |
| F03 | P1.4 | `e2e/specs/auth/F03-password-reset.spec.ts` |
| F04 | P1.5 | `e2e/specs/org/F04-invite-staff.spec.ts` |
| F05 | P1.5 | `e2e/specs/org/F05-create-organization.spec.ts` |
| F06 | P1.5 + P2.8 | `e2e/specs/org/F06-exam-scoped-roles.spec.ts` |
| F07 | P1.5 + P1.10 | `e2e/specs/org/F07-context-switch.spec.ts` |
| F08 | P1.3 + P1.5 | `e2e/specs/org/F08-data-isolation.spec.ts` |
| F09 | P2.3 + P2.8 | `e2e/specs/cms/F09-question-forms.spec.ts` |
| F10 | P2.8 | `e2e/specs/cms/F10-preview-as-learner.spec.ts` |
| F11 | P2.7 | `e2e/specs/cms/F11-excel-import.spec.ts` |
| F12 | P2.2 | `e2e/specs/cms/F12-media.spec.ts` |
| F13 | P2.6 | `e2e/specs/cms/F13-test-builder.spec.ts` |
| F14 | P2.4 | `e2e/specs/cms/F14-strategy-lesson.spec.ts` |
| F15 | P2.5 + P4.1 | `e2e/specs/cms/F15-score-tables.spec.ts` |
| F16 | P2.5 | `e2e/specs/cms/F16-grading-skill.spec.ts` |
| F17 | P1.6 + P2.9 | `e2e/specs/cms/F17-review-workflow.spec.ts` |
| F18 | P1.6 + P2.9 | `e2e/specs/cms/F18-versioning.spec.ts` |
| F19 | P3.1 | `e2e/specs/learning/F19-course-unlock-rules.spec.ts` |
| F20 | P3.1 | `e2e/specs/learning/F20-guest-catalog.spec.ts` |
| F21 | P6.5 | `e2e/specs/b2b/F21-shared-catalog.spec.ts` |
| F22 | P3.2 | `e2e/specs/learning/F22-lesson-practice.spec.ts` |
| F23 | P3.2 | `e2e/specs/learning/F23-chapter-test.spec.ts` |
| F24 | P4.5 | `e2e/specs/learning/F24-progress-by-plan.spec.ts` |
| F25 | P3.4 + P4.1 | `e2e/specs/exam/F25-toeic-full-test.spec.ts` |
| F26 | P3.4 + P4.1 | `e2e/specs/exam/F26-ielts-full-test.spec.ts` |
| F27 | P3.4 | `e2e/specs/exam/F27-audio-once.spec.ts` |
| F28 | P3.4 | `e2e/specs/exam/F28-autosave-offline.spec.ts` |
| F29 | P3.3 + P3.4 | `e2e/specs/exam/F29-resume-limit.spec.ts` |
| F30 | P3.3 | `e2e/specs/exam/F30-time-up.spec.ts` |
| F31 | P3.3 | `e2e/specs/exam/F31-idempotent-submit.spec.ts` |
| F32 | P3.4 | `e2e/specs/exam/F32-speaking-recorder.spec.ts` |
| F33 | P4.1 | `e2e/specs/grading/F33-auto-scoring.spec.ts` |
| F34 | P4.2 | `e2e/specs/grading/F34-ai-grading.spec.ts` |
| F35 | P4.3 | `e2e/specs/grading/F35-teacher-queue.spec.ts` |
| F36 | P4.3 | `e2e/specs/grading/F36-both-mode.spec.ts` |
| F37 | P4.2 | `e2e/specs/grading/F37-gemini-failure.spec.ts` |
| F38 | P4.4 | `e2e/specs/grading/F38-results-weakness.spec.ts` |
| F39 | P5.1 + P5.4 | `e2e/specs/billing/F39-b2c-plan.spec.ts` |
| F40 | P5.2 + P5.4 | `e2e/specs/billing/F40-vietqr-order.spec.ts` |
| F41 | P5.1 | `e2e/specs/billing/F41-entitlement-access.spec.ts` |
| F42 | P5.3 | `e2e/specs/billing/F42-org-license.spec.ts` |
| F43 | P5.3 | `e2e/specs/billing/F43-seat-assign-revoke.spec.ts` |
| F44 | P6.1 | `e2e/specs/b2b/F44-members.spec.ts` |
| F45 | P6.2 | `e2e/specs/b2b/F45-classes.spec.ts` |
| F46 | P6.2 | `e2e/specs/b2b/F46-assignments.spec.ts` |
| F47 | P6.3 | `e2e/specs/b2b/F47-schedule.spec.ts` |
| F48 | P6.3 | `e2e/specs/b2b/F48-attendance.spec.ts` |
| F49 | P6.4 | `e2e/specs/b2b/F49-reports-excel.spec.ts` |
| F50 | P6.5 | `e2e/specs/b2b/F50-center-grading-config.spec.ts` |
| F51 | P6.5 | `e2e/specs/b2b/F51-center-content.spec.ts` |
| F52 | P1.8 | `e2e/specs/notify/F52-in-app.spec.ts` |
| F53 | P1.8 | `e2e/specs/notify/F53-email.spec.ts` |
| F54 | P1.8 | `e2e/specs/notify/F54-telegram.spec.ts` |
| F55 | P1.8 | `e2e/specs/notify/F55-channel-prefs.spec.ts` |
| F56 | P4.5 | `e2e/specs/admin/F56-ai-monitoring.spec.ts` |
| F57 | P1.9 | `e2e/specs/admin/F57-audit-log.spec.ts` |
| F58 | P1.10 | `e2e/specs/common/F58-i18n-a11y.spec.ts` |

Nguồn chuẩn của ma trận: `e2e/features.json` (P1.12). Gate `scripts/check-e2e-coverage.mjs` đọc file này, tìm spec tương ứng và fail nếu thiếu file hoặc spec chỉ chứa `test.fixme`/`test.skip`.

## Môi trường hiện tại và phần chạy được ngay

Phiên phát triển hiện không có mạng, không có Docker, chưa có quyền GitHub. Chạy được: JDK 21, Node 22, Playwright, PostgreSQL 16. Vì vậy chạy trước **wave cục bộ** (file `2026-11-06-english9x-local-wave.md`): các phần thuần JDK của P1.2, P4.1, P5.1–P5.3, P3.1–P3.3 và schema/RLS của P1.3, cùng gate E2E của P1.12. Phần còn lại cần mạng để tải Spring Boot/JUnit/React và Docker cho Testcontainers/E2E.
