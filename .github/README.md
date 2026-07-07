# CI and branch protection

This directory holds the EIP continuous-integration pipeline and the `main` branch-protection ruleset. It implements TASK-0002 (story P0-E1-S2): CI enforces the [QualityGatePolicy](../engineering-operating-system/QualityGatePolicy.md) merge gates from the first PR.

## Pipeline

[`workflows/ci.yml`](workflows/ci.yml) runs on every pull request to `main` (and on push to `main`). Three jobs, mapped to gates per [CodingStandards §1](../engineering-operating-system/CodingStandards.md):

| Job | Stages | Gate |
|---|---|---|
| `backend` | Spotless · Checkstyle · Error Prone · NullAway · `build`/`check` (incl. Modulith `ModularityTests` + ArchUnit once TASK-0005 lands) · JaCoCo coverage ratchet | G1, G2 |
| `frontend` | ESLint · `tsc --noEmit` · `vite build` (pnpm pinned via corepack) | G1 |
| `security` | gitleaks secret scan · dependency review (PR only) | G3-automated |

The docs-lint job (G7) is added by TASK-0008. Coverage thresholds (≥85% `eip-core`, ≥75% elsewhere, per [TestingStrategy §1](../docs/testing/TestingStrategy.md)) live in `backend/buildSrc/src/main/kotlin/eip.java-conventions.gradle.kts`.

## Branch protection

[`rulesets/main-protection.json`](rulesets/main-protection.json) is the `main` ruleset as config-as-code: PR-only merges, one approving review, code-owner review, required status checks (`backend`, `frontend`, `security`), no force-push, no deletion.

**Applying it requires repository access (a push and admin API call)** — out of scope for the local TASK-0002 work. Once the branch is pushed, an admin applies it with:

```
gh api --method POST repos/{owner}/{repo}/rulesets --input .github/rulesets/main-protection.json
```

Until applied, the ruleset is the recorded, reviewable source of truth for the intended protection (AC-4). The required-check contexts (`backend`/`frontend`/`security`) match the `ci.yml` job names exactly.

## Local mirror

CI mirrors what a developer runs locally (no CI-only steps):

```
cd backend && ./gradlew build check          # backend job
pnpm --dir frontend lint && pnpm --dir frontend typecheck && pnpm --dir frontend build   # frontend job
```
