# EIP developer workflow entry points. Scaffold stub (TASK-0001) — the Compose dev-stack targets
# (dev-up/dev-down) land in TASK-0003 (P0-E1-S3); CI-facing targets follow in TASK-0002.
.DEFAULT_GOAL := help

.PHONY: help
help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

.PHONY: backend-build
backend-build: ## Build the backend (Gradle multi-module)
	cd backend && ./gradlew build

.PHONY: frontend-build
frontend-build: ## Build the frontend (Vite)
	pnpm --dir frontend install && pnpm --dir frontend build
