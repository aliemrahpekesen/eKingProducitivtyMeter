/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.spi;

/**
 * LLM SPI 0.1 (NFR-060 semver): thin chat-completion contract; a LangChain4j-backed implementation
 * becomes the default when RAG/agent phases land (ADR-024). Implementations are synchronous,
 * blocking, and stateless — one call in, one response out, no session/conversation state kept
 * between calls.
 */
@FunctionalInterface
public interface LlmClient {

  /**
   * Completes one chat request against the configured provider.
   *
   * @param request the system/user prompts and generation parameters
   * @return the provider's response
   * @throws com.eip.ai.api.LlmUnavailableException if the provider is unreachable, times out, or
   *     returns a non-2xx response
   */
  LlmResponse complete(LlmRequest request);
}
