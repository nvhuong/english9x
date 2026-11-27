.PHONY: help start stop restart status ps logs seed reset build test mcp mcp-build mcp-test

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
	@echo "  make mcp       - Khởi chạy MCP Server kết nối AI Agents (Antigravity/Claude)"
	@echo "  make mcp-test  - Kiểm thử bộ test suite của MCP Server"
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
	@./build.sh

test:
	@pnpm -C frontend run test --run

mcp:
	@./scripts/mcp.sh start

mcp-build:
	@./scripts/mcp.sh build

mcp-test:
	@./scripts/mcp.sh test

