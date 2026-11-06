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
- Đã xong (wave cục bộ): `backend/e9x-domain` (lõi nghiệp vụ thuần Java), migration `V202611060001__org_baseline.sql` + `backend/db-tests` (RLS), gate E2E.
- Kiểm chứng offline: `./scripts/test-domain.sh` 129/129 (shim JUnit), `./scripts/test-db.sh` 14/14 (cần binary PostgreSQL 16 cục bộ), gate E2E 0/58.
- Chưa có: `e9x-app` (Spring Boot), `frontend/`, `e2e/` specs, `infra/` Compose — bắt đầu ở wave **W0** (P1.1).

## Việc đầu tiên trên máy có mạng + Docker
```bash
git checkout release/v1
# 1) Xác nhận lõi domain bằng JUnit 5 thật (phải 129 test xanh như shim)
docker run --rm -v "$PWD":/w -w /w/backend maven:3.9-eclipse-temurin-21 mvn -B -pl e9x-domain test
# 2) Test RLS trên PostgreSQL 16
docker run --rm -v "$PWD":/w -w /w postgres:16 bash scripts/test-db.sh
# 3) Đẩy lên GitHub (không push --force, không merge main)
git push -u origin main release/v1
```
Sau đó thực hiện W0 theo `2026-11-06-english9x-p1-foundation.md` (P1.1 Monorepo/CI/Compose).
