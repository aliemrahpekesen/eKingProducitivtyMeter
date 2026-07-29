# Simulation Dataset — deterministic friction source (v0.1)

The fixed dataset the `SimulationConnector` (`eip-connectors`) emits through the real ingestion path
(TASK-0016). It replaces manual dashboard seeding: every number the platform shows is computed from
this data as it travels connector → `staging.raw_simulation` → normalization → correlation → analytics.
No real Jira/Bitbucket/GitHub/GitLab/Sonar connectivity. No person identifiers anywhere (NFR-071).

## Determinism

Every timestamp is a fixed base instant (`2026-01-05T09:00:00Z`) plus a whole-hour offset — no clock
read, no randomness. A replay produces byte-identical raw records, so ingestion is idempotent
(content-hash keyed) and every derived metric is reproducible. Verified by `SimulationDatasetTest`.

## Shape

**79 records** across **6 streams** for **3 teams**, in a fixed emission order (per team, per item:
work item → transitions → pull request → reviews → build → quality gate).

| Stream | Source system | Count | Key fields |
|---|---|---|---|
| `work_item` | jira | 9 | key, team, type, title, status, createdAt, resolvedAt |
| `work_item_transition` | jira | 33 | workItemKey, seq, fromState, toState, at |
| `pull_request` | bitbucket | 9 | key, workItemKey, team, sourceBranch, createdAt, mergedAt |
| `code_review` | bitbucket | 10 | key, pullRequestKey, workItemKey, outcome, requestedAt, completedAt |
| `build` | ci | 9 | key, pullRequestKey, workItemKey, status, startedAt, finishedAt |
| `quality_gate` | sonarqube | 9 | key, pullRequestKey, buildKey, workItemKey, status, evaluatedAt |

Each raw record also carries provenance: `sourceSystem`, `sourceInstance` (`sim`), and an immutable
`externalId` (`<sourceSystem>:<naturalKey>`, distinct from the natural key — AD-14).

## Cross-tool identifiers

The correlator stitches the full chain from explicit keys in the payloads:

```
work_item.key ─┐
               ├─ pull_request.workItemKey
pull_request.key ─┬─ code_review.pullRequestKey
                  ├─ build.pullRequestKey
                  └─ quality_gate.pullRequestKey
build.key ───────── quality_gate.buildKey
```

## Teams & flow patterns

Deliberately different flow shapes so the computed friction ranks them (worst-first Platform >
Payments > Web):

- **Platform** — worst: a blocked-heavy item with a **rework loop** (changes-requested → back to
  in-progress → re-review), plus a long review wait. States exercised: `TODO`, `IN_PROGRESS`,
  `BLOCKED`, `IN_REVIEW`, `DONE`.
- **Payments** — middling: one blocked item (4h) and one moderate review wait.
- **Web** — clean flow: short review waits, no blocking, no rework.

The resulting friction scores and component breakdown are documented in
[EngineeringFriction.md](EngineeringFriction.md).

## Anti-surveillance

No payload carries an author, assignee, reviewer, user, email, or login field. Reviews and work items
attribute activity to **artifacts and teams only** — asserted by `SimulationDatasetTest`.
