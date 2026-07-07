-- Enable pgvector in the local dev database (DockerCompose.md §1 layout, §3 normative excerpt).
-- Runs once via docker-entrypoint-initdb.d on first init (empty data dir); `make dev-down` drops
-- the volume so the next `make dev-up` re-runs it. In production, extension enablement is a Flyway
-- migration (eip-migrate), not an initdb script.
CREATE EXTENSION IF NOT EXISTS vector;
