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

## Trạng thái hiện tại (nhánh `release/v1`)
- Đã xong: toàn bộ **P1 Foundation** (W0–W2): `backend/e9x-domain`, `backend/e9x-app` (Spring Boot Modulith: identity, org, workflow, notification, audit, platform.jobs), 10 migration, `frontend/` (shell, design system, i18n, xác thực, tổ chức, thông báo, nhật ký), `e2e/` (Playwright trên Compose thật), `infra/` (Compose, Caddy, sao lưu và khôi phục), review cuối P1 đã sửa xong.
- Kiểm chứng: `E9X_A11Y=1 ./scripts/verify.sh`, `docker run --rm -v "$PWD":/w -w /w postgres:16 bash scripts/test-db.sh`, `./scripts/e2e.sh`. Số liệu mới nhất ở báo cáo của lần chạy, không ghi cứng ở đây. Gate E2E còn chưa đạt tới W10 (`coverage-progress`).
- Chưa có trên GitHub: `ci.yml` chưa từng chạy trên runner (`release/v1` chưa push), xem finding M5 của review cuối.
- **Việc tiếp theo: wave W3** (P2.1 catalog, P2.2 media theo `2026-11-06-english9x-p2-content.md`; và P5.1 gói + đơn theo plan P5, theo bảng wave của overview), đọc các ghi chú ngày 2026-11-08 trong từng task. Chạy thử stack: mục "Run the stack" của `README.md`.
