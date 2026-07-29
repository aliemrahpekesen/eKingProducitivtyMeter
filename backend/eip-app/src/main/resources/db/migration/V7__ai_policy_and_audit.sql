-- V7 — core.tenant_ai_policy + ai.llm_call_audit (M6-A, ADR-024): the opt-in, per-tenant AI
-- explanation layer's configuration and call ledger. AI is OFF by default per tenant
-- (`enabled boolean NOT NULL DEFAULT false`) and only ever EXPLAINS already-computed,
-- team-level deterministic results (FR-057/NFR-071) — it never persists anything else. Both
-- tables are a deliberate v0.1-scoped subset of the fuller `ai.llm_provider`/`ai.agent_run`
-- schema DatabasePlan §2 anticipates for the later agent-runtime/RAG phase (same "ship the v0.1
-- shape now" pattern as V6/ADR-023); ADR-024 documents the narrower scope. RLS via
-- R__rls_policies (additive).

-- core.tenant_ai_policy is hosted in the `core` schema (tenant configuration, alongside
-- core.secret/core.connector) even though the table is owned and evolved by eip-ai — the same
-- split DatabasePlan §2 already uses for other modules' tenant-config rows.
CREATE TABLE core.tenant_ai_policy (
  tenant_id    uuid PRIMARY KEY,
  enabled      boolean NOT NULL DEFAULT false,
  provider     text CHECK (provider IN ('ollama','openai-compatible')),
  base_url     text,
  model        text,
  secret_id    uuid REFERENCES core.secret(id),
  temperature  numeric(3,2) NOT NULL DEFAULT 0,
  max_tokens   int NOT NULL DEFAULT 800,
  updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER tg_tenant_ai_policy_touch BEFORE UPDATE ON core.tenant_ai_policy
  FOR EACH ROW EXECUTE FUNCTION core.tg_touch_updated_at();

-- ai.llm_call_audit — hash-only call ledger (log-hygiene, ADR-024): prompts embed the tenant's
-- own metric data, so only their SHA-256 + character count are stored, never the text itself;
-- same for the response. The audit proves a call happened and its SIZE/shape (purpose, provider,
-- model, latency, outcome), never its content. One row is written for EVERY call attempt,
-- including failures and numeric-cross-check rejections (`status`).
CREATE TABLE ai.llm_call_audit (
  id               uuid PRIMARY KEY,
  tenant_id        uuid NOT NULL,
  purpose          text NOT NULL CHECK (purpose IN ('EXPLAIN_DASHBOARD','REPORT_NARRATIVE')),
  provider         text NOT NULL,
  model            text NOT NULL,
  prompt_sha256    text NOT NULL,
  prompt_chars     int NOT NULL,
  response_sha256  text,
  response_chars   int,
  latency_ms       int,
  status           text NOT NULL CHECK (status IN ('OK','FAILED','REJECTED')),
  error            text,
  created_at       timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_llm_call_audit_tenant_created ON ai.llm_call_audit (tenant_id, created_at DESC);
