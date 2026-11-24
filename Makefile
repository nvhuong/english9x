.PHONY: help start stop restart status ps logs seed reset build test

help:
	@echo "=============================================================================="
	@echo "🛠️  ENGLISH9X - CÁC LỆNH VẬN HÀNH HỆ THỐNG (MAKEFILE)"
	@echo "=============================================================================="
	@echo "  make start     - Khởi động toàn bộ hệ thống (Docker containers)"
	@echo "  make stop      - Dừng hệ thống an toàn (Bảo toàn dữ liệu)"
	@echo "  make restart   - Khởi động lại hệ thống"
	@echo "  make status    - Kiểm tra trạng thái các dịch vụ & thống kê dữ liệu"
	@echo "  make logs      - Xem log thời gian thực của Backend App"
	@echo "  make seed      - Nạp dữ liệu mẫu một chạm (Users, Courses, Tests, Plans)"
	@echo "  make reset     - Xóa sạch dữ liệu cũ và khởi tạo lại hệ thống mới"
	@echo "  make build     - Rebuild lại các Docker images"
	@echo "  make test      - Chạy bộ kiểm thử (Frontend & Backend unit tests)"
	@echo "=============================================================================="

start:
	@./start.sh

up: start

stop:
	@./stop.sh

down: stop

restart:
	@./restart.sh

status:
	@./status.sh

ps: status

logs:
	@./logs.sh

seed:
	@./seed.sh

reset:
	@./reset.sh

build:
	@docker compose --env-file infra/.env -f infra/docker-compose.yml -f infra/docker-compose.e2e.yml build

test:
	@pnpm -C frontend run test --run
