/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * LLM provider implementations (module-internal): {@code AiHttp} (a minimal JDK HttpClient JSON
 * POST helper, mirroring {@code com.eip.connectors.http.SourceHttp}'s style), the Ollama and
 * OpenAI-compatible {@code com.eip.ai.spi.LlmClient} implementations, and {@link
 * com.eip.ai.providers.LlmClientFactory}, the one public entry point the application layer uses to
 * select a client from the tenant's configured provider (ADR-024). No external egress unless the
 * tenant explicitly configures a provider base URL — nothing here ever calls out on its own.
 *
 * <p>Null-marked (JSpecify): every reference is non-null unless annotated {@code @Nullable}.
 */
@NullMarked
package com.eip.ai.providers;

import org.jspecify.annotations.NullMarked;
