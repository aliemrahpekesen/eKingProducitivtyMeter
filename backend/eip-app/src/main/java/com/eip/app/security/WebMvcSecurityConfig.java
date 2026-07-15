/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link PermissionEnforcementInterceptor} over the {@code /api/v1} handler mapping only
 * — actuator, OpenAPI docs, and error dispatch never pass through deny-by-default RBAC
 * (SecurityModel §4).
 */
@Configuration
public class WebMvcSecurityConfig implements WebMvcConfigurer {

  private final PermissionEnforcementInterceptor interceptor;

  public WebMvcSecurityConfig(PermissionEnforcementInterceptor interceptor) {
    this.interceptor = interceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(interceptor).addPathPatterns("/api/v1/**");
  }
}
