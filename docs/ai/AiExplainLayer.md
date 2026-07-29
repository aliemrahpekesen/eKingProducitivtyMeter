# AI Explanation Layer (M6-A)

A thin, **opt-in** layer that turns already-computed, team-level EIP metrics into short prose
explanations — nothing more. It is the first slice of the `eip-ai` module's runtime story
(ADR-024); the full agent runtime, RAG pipeline, and MCP client/server `../ai/AgentArchitecture.md`
and `../ai/RAGArchitecture.md` describe are later phases and are not implemented yet.

Related documents: `../adr/ADR-024-ai-explanation-layer.md` (the design decision this page
operationalizes), `../ai/AgentArchitecture.md` (the eventual full agent runtime), `../architecture/
SecurityModel.md` §4 (the `ai.agent.invoke`/`ai.policy.manage` permissions).

## 1. The one rule that governs everything here

**AI explains. It never computes.** Every number this layer's output can ever contain was already
computed by a deterministic engine (the friction metric, the weekly trend aggregation, the
recommendation rules, or a generated report) and was already visible on the dashboard or report
before the AI call happened. The layer's job is prose, not arithmetic — and every number the
generated prose contains is mechanically verified against the numbers it was given before the
response is returned (§4). Team-level only: nothing here sees or can reference an individual
person, because nothing individual-level exists in the canonical model (NFR-071).

## 2. Off by default, per tenant

Every tenant starts with the layer **disabled**. `GET /api/v1/ai/status` reports a plain `{
"enabled": false }` until a `TENANT_ADMIN` explicitly turns it on — nothing calls out to any LLM
provider before that happens, ever. Enabling requires an explicit provider, base URL, and model
(`PUT /api/v1/ai/policy`); the OpenAI-compatible provider additionally requires a stored API key.

```json
PUT /api/v1/ai/policy
{
  "enabled": true,
  "provider": "ollama",
  "baseUrl": "http://ollama.internal:11434",
  "model": "llama3",
  "temperature": 0.2,
  "maxTokens": 600
}
```

Any field left out of a later `PUT` keeps its current stored value — rotating just the secret, or
just flipping `enabled` off and back on, does not require repeating the whole configuration.
`GET /api/v1/ai/policy` never returns the stored secret's plaintext; it reports only `hasSecret`.

## 3. Providers

| Provider | `provider` value | Where it runs | Egress |
|---|---|---|---|
| Self-hosted Ollama | `ollama` | Anywhere the tenant points `baseUrl` at — typically inside the same network/air-gapped install | None beyond `baseUrl`; no API key needed |
| Any OpenAI-compatible endpoint | `openai-compatible` | A tenant-configured third-party or self-hosted endpoint speaking the OpenAI chat-completions shape | The tenant's own configured `baseUrl`, with a Bearer API key |

**Air-gapped installs** use `ollama` pointed at a local model server with no path to the public
internet — this is the intended default for an on-premise deployment. No provider is ever contacted
unless a tenant configures its `baseUrl` explicitly; there is no default, hardcoded, or fallback
endpoint.

## 4. What is and isn't sent, and where

- **Sent to the provider:** a fixed system prompt (the guardrail text, §5 — no tenant data) and a
  user prompt built only from the same deterministic reads the dashboard/report already shows
  (friction summary, weekly trends, recommendations, or a report's totals/team sections). No
  individual-level data ever enters this prompt, because none exists to send.
  Nothing is sent unless the tenant has explicitly enabled the layer and configured a provider.
- **Never sent anywhere:** the provider API key, beyond the Authorization header of the one HTTP
  call it authenticates.
- **Returned to the caller:** the generated narrative, the provider/model that produced it, the
  numbers it cited (verified, §4 below), and a fixed disclaimer every response carries verbatim:
  *"AI-generated explanation of deterministic metrics. Verify against the numbers shown; the AI did
  not compute anything."*
- **Persisted:** exactly one hash-only audit row per call attempt (`ai.llm_call_audit`) — the
  SHA-256 and character count of the prompt and (if any) the response, never the text itself. The
  audit proves a call happened and its size/shape; it cannot be used to reconstruct what was said.
  Nothing else is persisted — a narrative is not stored anywhere; asking again regenerates it.

## 5. Guardrails

1. **Deterministic-only composition.** The prompt is built exclusively from already-computed,
   already-displayed reads — never a value the AI itself derives.
2. **Fixed system prompt** sent on every call, verbatim: *"You explain ALREADY-COMPUTED team-level
   engineering flow metrics. Use ONLY the numbers provided. Never invent numbers. Never mention,
   rank, or infer anything about individual people. Answer in concise en-US prose, max ~250 words."*
3. **Mechanical numeric cross-check.** Every number the returned narrative cites (normalized —
   `85%` and `85` count as the same number, so do `17.0` and `17`) must appear among the numbers
   the prompt actually offered it. A narrative citing anything else is discarded, never returned —
   the caller sees a `502 /problems/ai-rejected`, not a plausible-looking but unverifiable answer.
   This check is exact, not fuzzy: there is no allowance for "close enough" or for small ordinals a
   model might reasonably invent in prose — the honest cost of a strict, no-exemption rule is that a
   phrase like "the top 3 teams" will usually happen to match some real number in the data by
   coincidence rather than because the model meant that specific value.
4. **Team-level only.** No individual person is ever named, ranked, or inferred, because the
   canonical data model this layer reads from carries no individual-level signal (NFR-071).
5. **Fail closed.** A disabled tenant is refused before any provider is ever contacted
   (`409 /problems/ai-disabled`); an unreachable/erroring provider surfaces honestly
   (`502 /problems/ai-upstream`) rather than a silent fallback or a fabricated answer.
6. **Hash-only audit, always written** — including on failure and rejection — so every attempt is
   accounted for without ever persisting the tenant's metric data or the model's output text.

## 6. Endpoints

| Endpoint | Permission | Purpose |
|---|---|---|
| `GET /api/v1/ai/policy` | `ai.policy.manage` | Read the tenant's current AI configuration (never the secret's plaintext) |
| `PUT /api/v1/ai/policy` | `ai.policy.manage` | Update the configuration (partial — unset fields keep their current value) |
| `GET /api/v1/ai/status` | `dashboard.view` | Whether the layer is enabled — a minimal signal any dashboard viewer can read, to decide whether to offer AI actions at all |
| `POST /api/v1/insights/explain` | `ai.agent.invoke` | Explain the current dashboard's friction/trends/recommendations for a given trend window |
| `POST /api/v1/reports/{id}/narrative` | `ai.agent.invoke` | Narrate one already-generated report's content |

`ai.policy.manage` is a `TENANT_ADMIN`-only permission (SecurityModel §4); `ai.agent.invoke` is
granted to every role above `VIEWER`/`EXECUTIVE_VIEWER`/`SECURITY_AUDITOR`/`PLATFORM_ADMIN`.

## 7. Operator checklist

1. Decide a provider: `ollama` for an air-gapped/self-hosted default, or `openai-compatible` for an
   approved third-party endpoint.
2. `PUT /api/v1/ai/policy` with `enabled: true`, the provider, `baseUrl`, and `model` (plus a
   `secret` for `openai-compatible`).
3. Confirm `GET /api/v1/ai/status` reports `enabled: true`.
4. Try `POST /api/v1/insights/explain` with a small `weeks` window and read the disclaimer — the
   response is safe to show to any viewer with `ai.agent.invoke`, since every number in it has
   already been verified against the same deterministic data the dashboard itself displays.
