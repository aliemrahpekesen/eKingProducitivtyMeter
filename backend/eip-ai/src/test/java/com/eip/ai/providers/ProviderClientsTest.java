/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.ai.providers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.eip.ai.api.LlmUnavailableException;
import com.eip.ai.spi.LlmRequest;
import com.eip.ai.spi.LlmResponse;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Proves the Ollama and OpenAI-compatible {@code LlmClient} implementations against WireMock'd
 * endpoints: request shape, the Bearer auth header on the OpenAI-compatible path (and its absence
 * on Ollama), and non-2xx responses mapping to {@link LlmUnavailableException}.
 */
@Tag("integration")
class ProviderClientsTest {

  private final WireMockServer server =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  @BeforeEach
  void start() {
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  @Test
  void ollama_client_posts_the_chat_shape_and_parses_message_content() {
    server.stubFor(
        post(urlPathEqualTo("/api/chat"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"model\":\"llama3\",\"message\":{\"role\":\"assistant\",\"content\":\"The team's friction score is 62.\"}}")));

    OllamaClient client = new OllamaClient(server.baseUrl(), new AiHttp());
    LlmResponse response =
        client.complete(new LlmRequest("system prompt", "user prompt", "llama3", 0.2, 500));

    assertThat(response.text()).isEqualTo("The team's friction score is 62.");
    assertThat(response.modelReported()).isEqualTo("llama3");
    server.verify(
        postRequestedFor(urlPathEqualTo("/api/chat"))
            .withRequestBody(
                com.github.tomakehurst.wiremock.client.WireMock.containing("\"stream\":false")));
  }

  @Test
  void openai_compatible_client_sends_a_bearer_auth_header_and_parses_the_first_choice() {
    server.stubFor(
        post(urlPathEqualTo("/v1/chat/completions"))
            .withHeader("Authorization", equalTo("Bearer secret-key"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"model\":\"gpt-x\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"85 items resolved.\"}}]}")));

    OpenAiCompatibleClient client =
        new OpenAiCompatibleClient(server.baseUrl(), "secret-key", new AiHttp());
    LlmResponse response =
        client.complete(new LlmRequest("system prompt", "user prompt", "gpt-x", 0.2, 500));

    assertThat(response.text()).isEqualTo("85 items resolved.");
    assertThat(response.modelReported()).isEqualTo("gpt-x");
    server.verify(postRequestedFor(urlPathEqualTo("/v1/chat/completions")));
  }

  @Test
  void ollama_client_sends_no_authorization_header() {
    server.stubFor(
        post(urlPathEqualTo("/api/chat"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"message\":{\"content\":\"ok\"}}")));

    new OllamaClient(server.baseUrl(), new AiHttp())
        .complete(new LlmRequest("s", "u", "llama3", 0.2, 100));

    server.verify(postRequestedFor(urlPathEqualTo("/api/chat")).withoutHeader("Authorization"));
  }

  @Test
  void a_non_2xx_response_maps_to_llm_unavailable() {
    server.stubFor(post(urlPathEqualTo("/api/chat")).willReturn(aResponse().withStatus(500)));

    OllamaClient client = new OllamaClient(server.baseUrl(), new AiHttp());

    assertThatThrownBy(() -> client.complete(new LlmRequest("s", "u", "llama3", 0.2, 100)))
        .isInstanceOf(LlmUnavailableException.class)
        .hasMessageContaining("500");
  }

  @Test
  void an_unreachable_endpoint_maps_to_llm_unavailable() {
    String url = server.baseUrl();
    server.stop();
    OllamaClient client = new OllamaClient(url, new AiHttp());

    assertThatThrownBy(() -> client.complete(new LlmRequest("s", "u", "llama3", 0.2, 100)))
        .isInstanceOf(LlmUnavailableException.class);
  }
}
