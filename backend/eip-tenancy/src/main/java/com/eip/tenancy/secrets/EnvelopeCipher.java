/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.tenancy.secrets;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure AES-256-GCM envelope primitives (ADR-014): {@code encrypt} seals a payload with a random
 * per-secret DEK, {@code wrapDek}/{@code unwrapDek} seal the DEK under the KEK. Each output is
 * {@code 12-byte nonce || GCM ciphertext+tag}; a fresh random nonce per operation.
 */
public final class EnvelopeCipher {

  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  private EnvelopeCipher() {}

  /**
   * Generates a fresh 32-byte data-encryption key.
   *
   * @return the DEK
   */
  public static byte[] newDek() {
    byte[] dek = new byte[32];
    RANDOM.nextBytes(dek);
    return dek;
  }

  /**
   * Seals plaintext under the given key.
   *
   * @param key a 32-byte AES key
   * @param plaintext the payload
   * @return {@code nonce || ciphertext+tag}
   */
  public static byte[] seal(byte[] key, byte[] plaintext) {
    try {
      byte[] nonce = new byte[NONCE_BYTES];
      RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(TAG_BITS, nonce));
      byte[] sealed = cipher.doFinal(plaintext);
      byte[] out = new byte[NONCE_BYTES + sealed.length];
      System.arraycopy(nonce, 0, out, 0, NONCE_BYTES);
      System.arraycopy(sealed, 0, out, NONCE_BYTES, sealed.length);
      return out;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM seal failed", e);
    }
  }

  /**
   * Opens a sealed payload under the given key.
   *
   * @param key a 32-byte AES key
   * @param sealed {@code nonce || ciphertext+tag}
   * @return the plaintext
   */
  public static byte[] open(byte[] key, byte[] sealed) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES));
      return cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM open failed (wrong key or tampered payload)", e);
    }
  }

  /**
   * Convenience: seals a UTF-8 string.
   *
   * @param key a 32-byte AES key
   * @param text the payload
   * @return {@code nonce || ciphertext+tag}
   */
  public static byte[] sealText(byte[] key, String text) {
    return seal(key, text.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Convenience: opens a sealed UTF-8 string.
   *
   * @param key a 32-byte AES key
   * @param sealed {@code nonce || ciphertext+tag}
   * @return the plaintext string
   */
  public static String openText(byte[] key, byte[] sealed) {
    return new String(open(key, sealed), StandardCharsets.UTF_8);
  }
}
