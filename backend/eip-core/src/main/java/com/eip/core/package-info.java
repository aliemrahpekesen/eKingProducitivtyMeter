/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Shared-kernel module root (BackendPlan §3). Declared OPEN: the kernel's value types, event
 * envelope, error taxonomy, and SPI roots are, by definition, API for every other module.
 */
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.eip.core;
