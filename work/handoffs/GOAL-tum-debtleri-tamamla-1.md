# Goal-pause note — "/goal tüm debtleri tamamla"

**Not a TASK-NNNN handoff** — this tracks the session-scoped Stop-hook goal, which spans
TASK-0025 (done) and a not-yet-started follow-on. Written 2026-07-22 on a user pause request
(`/goal-pause`, which is not an implemented local command in this environment — no definition
file backs it, unlike `/goal-resume`; treated as a plain-English "stop now" instruction since the
intent was unambiguous).

## State

- **TASK-0025 (debt-paydown sweep) is DONE.** All 4 waves landed, CI green, `work/debt-register.md`
  fully reconciled against actual code/docs (not status reports — every claim spot-checked).
  Last commit: `aa7a63c` on `feature/TASK-0017-local-install-profiles`, pushed to origin.
- Nothing uncommitted. Nothing mid-edit. Safe to walk away from right now.
- The Stop-hook fired after TASK-0025 closed, correctly pointing out that "tüm debtleri tamamla"
  (complete ALL debts) is not satisfied by TASK-0025's scoped subset alone — several DEBT rows are
  still open in the register, some genuinely actionable now, some genuinely blocked on a decision
  this sweep was never authorized to make.

## Honest split: actionable now vs. genuinely blocked

**Actionable without any new architectural authorization** (candidate TASK-0026 scope):
- **DEBT-012 residual** — the `x-eip-permission` OpenAPI extension was never implemented (verified
  by grep: only referenced in a doc-comment). Small, mechanical, additive: an `OperationCustomizer`
  alongside DEBT-011's existing one + wiring the frontend `<Can>`/router guard to read it.
- **DEBT-018 residual** — `RawRecord.payload` is still a flat `Map<String,String>`, not arbitrary
  nested JSON. Additive SPI change; ConnectorFramework §3 already specifies the target shape.
- **DEBT-013/017 residual** — relocate the compute/normalize JDBC orchestration still sitting in
  `eip-app` into the owning modules (`eip-ingestion`/`eip-analytics`) per ADR-019's already-ratified
  ownership — no new decision needed, just execution.
- **DEBT-024** — the `audit.audit_event` write path (hash-chained records, chain-verifier job,
  retrofitting every already-logged security action). Sized **L** — the biggest single piece of
  remaining work, but not blocked on anything external, just large. Owning module not yet confirmed
  — check `ModuleOwnership.md` before assigning (was mid-grep on this when the permission classifier
  blocked the read and the pause arrived — re-run: `grep -n "audit" engineering-operating-system/ModuleOwnership.md`).

**Genuinely blocked — re-affirming, not silently dropping:**
- **DEBT-005 (residual)** — `infra/docker-compose/.env.example` CHANGE_ME convergence waits on an
  `eip-app`/`eip-migrate` Compose-container architecture decision nobody has requested. Building
  this now would mean making that architecture call unilaterally — out of scope for a debt sweep.
- **DEBT-021 (residual) + DEBT-023** — both depend on the agent-runtime phase ADR-024 *deliberately
  defers* to later (LangChain4j adoption, job-runner/MinIO, per-agent metric breakdown). Building
  either now would override an already-ratified ADR decision without being asked — not a debt-sweep
  call to make.

These three are legitimate standing exceptions to "tüm debtleri tamamla," not evasions — each has
a real architectural dependency that this sweep has no mandate to resolve.

## Next concrete step, if/when resumed

1. Author `work/tasks/TASK-0026.md` scoping exactly the four actionable items above, explicitly
   restating (in the task's own scope section) why DEBT-005/021/023 remain out of reach — same
   pattern TASK-0025 used.
2. Re-run the grep that got interrupted (`ModuleOwnership.md` audit-module ownership) before
   assigning DEBT-024's write-set.
3. Wave it the same way as TASK-0025: Sonnet 5 subagents write code, orchestrator reviews/gates/
   commits, single-writer discipline on `work/debt-register.md`.
4. DEBT-024 (audit subsystem) is the size outlier — likely deserves its own wave/task rather than
   bundling with the three smaller items, given its L size vs. their S/M.

## Constraints to preserve (unchanged from TASK-0025)

- Never merge PR #1 (`integration/SPRINT-00` → base) — still explicitly deferred by the user.
- `integration/SPRINT-00` mirrors `feature/TASK-0017-local-install-profiles`'s tip via fast-forward
  push after each landed commit (keeps PR #1's CI current without merging it) — the mirror-push for
  `aa7a63c` was blocked by the auto-mode permission classifier and never completed; ask the user
  before retrying, or skip it (the commit is docs-only, so no CI signal is actually lost).
- Single writer on `work/debt-register.md` — only the orchestrator edits it, never a background
  agent in parallel.
- Never commit without being explicitly asked (this file itself is uncommitted — intentionally,
  pending the user's instruction).
