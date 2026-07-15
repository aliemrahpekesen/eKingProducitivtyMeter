# EIP developer workflow entry points. `make dev-up` boots the local infrastructure dev stack
# (Compose); backend/frontend run on the host. CI-facing build targets below mirror the pipeline.
.DEFAULT_GOAL := help

COMPOSE := docker compose -f infra/docker-compose/docker-compose.yml --profile core --profile observability
COMPOSE_ENV := infra/docker-compose/.env
# Long-running services only — `up --wait` must not monitor the one-shot minio-init (it exits 0,
# which compose reports as a failure). minio-init runs separately, to completion, after the wait.
DEV_SERVICES := postgres redis kafka minio keycloak otel-collector prometheus grafana

.PHONY: help
help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

.PHONY: dev-up
dev-up: ## Boot the local infra dev stack (Postgres, Redis, Kafka, MinIO, Keycloak, OTel, Prometheus, Grafana)
	@test -f $(COMPOSE_ENV) || cp infra/docker-compose/.env.example $(COMPOSE_ENV)
	$(COMPOSE) --env-file $(COMPOSE_ENV) up -d --wait --wait-timeout 900 $(DEV_SERVICES)
	$(COMPOSE) --env-file $(COMPOSE_ENV) run --rm minio-init
	@$(MAKE) --no-print-directory dev-urls

.PHONY: dev-down
dev-down: ## Stop the dev stack and remove its volumes
	$(COMPOSE) down -v --remove-orphans

.PHONY: dev-stop
dev-stop: ## Stop the dev stack but keep volumes (data persists)
	$(COMPOSE) stop

.PHONY: dev-ps
dev-ps: ## Show dev-stack service status
	$(COMPOSE) ps

.PHONY: dev-urls
dev-urls: ## Print local endpoints and sample dev credentials
	@echo ""
	@echo "  EIP local dev stack — endpoints (dev credentials from infra/docker-compose/.env.example):"
	@echo "  ---------------------------------------------------------------------------------------"
	@echo "  PostgreSQL   postgresql://eip:eip_dev_pw@localhost:5432/eip"
	@echo "  Redis        redis://localhost:6379"
	@echo "  Kafka        localhost:29092  (in-network: kafka:9092)"
	@echo "  MinIO API    http://localhost:9000        console http://localhost:9001  (eip_minio / eip_minio_dev_pw)"
	@echo "               buckets: eip-artifacts, eip-ingest"
	@echo "  Keycloak     http://localhost:8180/auth   realm 'eip'  (admin / admin_dev_pw)"
	@echo "  OTel OTLP    grpc localhost:4317          http localhost:4318"
	@echo "  Prometheus   http://localhost:9090"
	@echo "  Grafana      http://localhost:3001        (admin / admin_dev_pw)"
	@echo ""
	@echo "  Backend/frontend run on the host:  make backend-build  |  make frontend-build"
	@echo ""

.PHONY: backend-build
backend-build: ## Build the backend (Gradle multi-module)
	cd backend && ./gradlew build

.PHONY: frontend-build
frontend-build: ## Build the frontend (Vite)
	pnpm --dir frontend install && pnpm --dir frontend build

.PHONY: install
install: ## Full local install (all infra + backend + frontend). ENV=dev|test|preprod (default dev); ports overridable
	@bash scripts/install/install.sh --env $(or $(ENV),dev)

.PHONY: stop
stop: ## Stop everything `make install` started (keeps data volumes)
	@bash scripts/install/stop.sh

.PHONY: uninstall
uninstall: ## DESTRUCTIVE: remove all EIP containers and data volumes
	@bash scripts/install/uninstall.sh

.PHONY: demo-up
demo-up: ## One-command local demo (fixed demo tenant preloaded). Override ports: POSTGRES_PORT / EIP_BACKEND_PORT / VITE_PORT
	@test -f $(COMPOSE_ENV) || cp infra/docker-compose/.env.example $(COMPOSE_ENV)
	@bash scripts/demo/demo-up.sh

.PHONY: demo-down
demo-down: ## Stop the demo frontend + backend + Postgres started by `make demo-up` (any ports)
	@bash scripts/demo/demo-down.sh
