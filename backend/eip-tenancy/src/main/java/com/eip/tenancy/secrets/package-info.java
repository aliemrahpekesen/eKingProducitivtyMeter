/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Envelope-encrypted secret storage (ADR-014, SecurityModel): a per-secret random DEK encrypts the
 * payload with AES-256-GCM, the DEK is wrapped by the KEK (master key from the environment), and
 * only ciphertexts touch {@code core.secret}. Plaintext is never logged, never returned by any API,
 * and revealed only to in-process consumers (connector sync, M2).
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.tenancy.secrets;

import org.jspecify.annotations.NullMarked;
