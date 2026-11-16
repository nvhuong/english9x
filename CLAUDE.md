# English9x — hướng dẫn cho Claude Code

Nền tảng dạy & luyện thi tiếng Anh (v1: IELTS AC/GT + TOEIC L&R, B2C + B2B). Java 21 / Spring Boot 3 / Spring Modulith, React 18 + TypeScript, PostgreSQL 16 (RLS), Docker Compose.

## Đọc trước khi làm
1. Spec (bắt buộc, là nguồn chân lý): `docs/superpowers/specs/2026-11-06-english9x-design.md`
2. Tổng quan plan, Global Constraints, Review Focus, wave W0–W10, ma trận F01–F58: `docs/superpowers/plans/2026-11-06-english9x-v1-overview.md`
3. Plan từng dự án con: `docs/superpowers/plans/2026-11-06-english9x-p{1..6}-*.md`
4. Ledger wave cục bộ (rulings, lỗi đã hoãn/park): `docs/superpowers/plans/2026-11-06-english9x-local-wave-ledger.md`

## Quy trình (Superpowers)
- superpowers:subagent-driven-development + superpowers:using-git-worktrees; mỗi task một worktree từ `release/v1`, TDD, review độc lập, merge vào `release/v1`.
- **Không merge vào `main`.** PR `release/v1 → main` chỉ mở khi `./scripts/verify.sh` xanh và **E2E F01–F58 đạt** (`node scripts/check-e2e-coverage.mjs` exit 0 và Playwright xanh).
- Không đổi chính sách: gói **không giới hạn lượt chấm AI** (`e9x.grading.ai.per-user-daily-limit=0`). Thông số tự đặt: spec §15.1, đều là khoá cấu hình.
- Không tuyên bố hoàn tất khi chưa có output test thật.

## Trạng thái hiện tại (nhánh `developer`, tích hợp; ngày 2026-11-16)
- **Nhánh:** không có `release/v1` trong repo. Mọi wave W0–W10 đã merge vào `developer`. Khi mở PR lên `main` thì PR từ `developer`.
- **Code W0–W10 đã có:** P1–P6 (identity, org, workflow, catalog, media, course, learning, exam, grading, analytics, billing, classroom, notification, privacy), frontend đầy đủ 8 màn chính, infra (Compose, Caddy, backup/restore, k6).
- **E2E gate:** `node scripts/check-e2e-coverage.mjs` → covered 56/58. Còn partial: **F13** (đề đủ cỡ TOEIC 200 / IELTS 4 kỹ năng, cần ~100 câu đã publish) và **F16** (`dùng khi chấm`: seed API chưa trả version id của skill trong evaluation). Chi tiết trong `e2e/partial-features.json`.
- **OpenAPI:** `openapi.json` và client sinh ra đang lệch với backend sau T6 (`DryRunResult`, `TestOverride`). Cần chạy `scripts/refresh-openapi.sh` (cần Docker) trước khi `OpenApiSnapshotIT` và `ApiClientGen` pass.
- **CI:** job `e2e` còn `continue-on-error` và `E9X_E2E_GATE` chưa strict, có chủ đích: gỡ khi `partial-features.json` rỗng.
- **Chưa từng chạy** (môi trường phát triển không có Docker): mọi spec Playwright, Testcontainers IT, `verify.sh`, `test-db.sh`, `e2e.sh`, `BackupRestoreIT`, diễn tập `infra/backup/drill-restore-inplace.sh`, k6 `infra/k6/exam-50.js`. Chỉ các kiểm tra offline đã chạy: `npx tsc --noEmit -p e2e`, Vitest/typecheck frontend cho file đã đổi, `mvn compile`, `mvn test` đơn vị đã chọn, `scripts/test-infra.sh --no-docker` (21/21).

## Việc cần làm trên máy có Docker (trước PR `developer → main`)
1. `./scripts/verify.sh` (và `E9X_A11Y=1 ./scripts/verify.sh`), `docker run --rm -v "$PWD":/w -w /w postgres:16 bash scripts/test-db.sh`, `./scripts/e2e.sh`.
2. `scripts/refresh-openapi.sh`, rồi commit snapshot.
3. Chạy E2E F58 và các spec mới của W10 (axe thật có thể báo lỗi tương phản ở dark mode trên phòng thi, kết quả, đơn hàng).
4. Diễn tập: `infra/backup/drill-restore-inplace.sh` trên staging (dry run trước: `E9X_DRILL_DRY_RUN=1`), k6 `exam-50.js` (cần 50 tài khoản học viên có quyền; chưa có seed script).
5. Viết F13 đề đủ cỡ và F16 `dùng khi chấm` (cần seed trả version id của skill).
6. Khi `partial-features.json` rỗng: gỡ `continue-on-error` của job `e2e` và bật `E9X_E2E_GATE=strict`.
