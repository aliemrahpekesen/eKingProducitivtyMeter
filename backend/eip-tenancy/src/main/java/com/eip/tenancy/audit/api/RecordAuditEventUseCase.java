/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.audit.api;

/**
 * Writes one audit row on the caller's current tenant-bound transaction (Wave 3B's retrofit target
 * — no call site is wired yet, this wave ships the port + write path only).
 */
public interface RecordAuditEventUseCase {

  /**
   * Records one audit event. Never throws into the caller's business path: a failed INSERT is
   * caught, logged, and counted by the implementation, not propagated — an audit-logging failure
   * must not fail the business operation it is auditing.
   *
   * @param event the event to record
   */
  void record(AuditEvent event);
}
