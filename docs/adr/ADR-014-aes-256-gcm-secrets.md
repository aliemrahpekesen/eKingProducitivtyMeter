# ADR-014: Encrypt secrets with AES-256-GCM envelope encryption behind a pluggable KMS SPI

- **Status:** Accepted
- **Approver:** R-CA (2026-07-06)
- **Affected anchors/modules:** [SecurityModel](../architecture/SecurityModel.md) (secrets, key management); `eip-core.secrets`; CC-1 / security-relevant (CC-2).

## Context

Backfilled in Phase 0 from [ArchitectureOverview §7](../architecture/ArchitectureOverview.md). Connector credentials and other secrets must never be stored or logged in plaintext, must support rotation, and must work where no cloud KMS exists — air-gapped installs (D6).

## Decision

Protect secrets with AES-256-GCM envelope encryption via a KMS SPI whose providers include env, file, and Vault. Data keys are wrapped by a master key from the KMS provider; plaintext secrets never persist and never appear in logs. Master-key **compromise** recovery is handled as a distinct procedure from routine rotation.

## Consequences

- **Positive:** works in air-gapped installs with no cloud KMS (D6); supports key rotation and integrates HashiCorp Vault where available; the envelope model isolates data-key rotation from master-key changes.
- **Negative:** the install owns key-management operations (master-key custody, rotation, compromise recovery); a lost master key without escrow renders wrapped secrets unrecoverable, so operational key custody becomes a first-class runbook.

## Alternatives rejected

- **A cloud KMS dependency** — rejected: unavailable in air-gapped installs (D6); the SPI still allows a cloud or Vault backend where policy permits.
- **At-rest disk/database encryption only** — rejected: does not protect field-level secrets in application memory or logs and gives no per-secret rotation story.
