/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.config;

import com.eip.core.domain.DefaultUuidV7Generator;
import com.eip.core.domain.UuidV7Generator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Composition-root wiring for the event-envelope/outbox spine (BackendPlan §6): the shared {@link
 * UuidV7Generator} every module mints event ids from, and {@link EnableScheduling} for the outbox
 * relay's {@code @Scheduled} poll ({@code com.eip.app.events.OutboxRelayScheduler}).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class EventingConfiguration {

  /**
   * The platform's single UUIDv7 id generator, over the system UTC clock.
   *
   * @return the generator
   */
  @Bean
  public UuidV7Generator uuidV7Generator() {
    return new DefaultUuidV7Generator(Clock.systemUTC());
  }
}
