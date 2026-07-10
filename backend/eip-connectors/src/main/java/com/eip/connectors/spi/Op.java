/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.connectors.spi;

/**
 * Whether a {@link RawRecord} asserts the source entity exists (upsert) or was removed (delete).
 */
public enum Op {
  UPSERT,
  DELETE
}
