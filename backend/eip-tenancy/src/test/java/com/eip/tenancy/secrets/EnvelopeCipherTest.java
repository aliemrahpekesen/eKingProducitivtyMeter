/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Proves the AES-256-GCM envelope primitives: round-trips, tamper/wrong-key failure, fresh nonces.
 */
class EnvelopeCipherTest {

  private final byte[] kek = EnvelopeCipher.newDek();

  @Test
  void seal_and_open_round_trip_text_and_binary() {
    byte[] dek = EnvelopeCipher.newDek();
    assertThat(EnvelopeCipher.openText(dek, EnvelopeCipher.sealText(dek, "gizli-token-42")))
        .isEqualTo("gizli-token-42");
    byte[] wrapped = EnvelopeCipher.seal(kek, dek);
    assertThat(EnvelopeCipher.open(kek, wrapped)).isEqualTo(dek);
  }

  @Test
  void wrong_key_and_tampered_payload_fail_loud() {
    byte[] dek = EnvelopeCipher.newDek();
    byte[] sealed = EnvelopeCipher.sealText(dek, "s");
    assertThatThrownBy(() -> EnvelopeCipher.open(EnvelopeCipher.newDek(), sealed))
        .isInstanceOf(IllegalStateException.class);
    sealed[sealed.length - 1] ^= 0x01;
    assertThatThrownBy(() -> EnvelopeCipher.open(dek, sealed))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void every_seal_uses_a_fresh_nonce() {
    byte[] dek = EnvelopeCipher.newDek();
    byte[] a = EnvelopeCipher.sealText(dek, "same");
    byte[] b = EnvelopeCipher.sealText(dek, "same");
    assertThat(a).isNotEqualTo(b); // nonce differs, so ciphertext differs
  }

  @Test
  void deks_are_32_bytes_and_unique() {
    assertThat(EnvelopeCipher.newDek()).hasSize(32).isNotEqualTo(EnvelopeCipher.newDek());
  }
}
