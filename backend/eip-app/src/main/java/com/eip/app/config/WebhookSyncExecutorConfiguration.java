/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The dedicated executor a webhook trigger's dispatched incremental sync runs on (TASK-0017 Wave
 * 2C): virtual threads, one per dispatched sync, so a slow/blocked source fetch never starves the
 * platform's other virtual-thread pools. {@code destroyMethod = "close"} runs {@link
 * ExecutorService}'s orderly shutdown (JDK 21) on context close, so in-flight syncs are given a
 * chance to finish rather than being abandoned mid-request.
 */
@Configuration(proxyBeanMethods = false)
public class WebhookSyncExecutorConfiguration {

  /**
   * Creates the webhook-sync executor bean.
   *
   * @return a virtual-thread-per-task executor
   */
  @Bean(destroyMethod = "close")
  public ExecutorService webhookSyncExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
