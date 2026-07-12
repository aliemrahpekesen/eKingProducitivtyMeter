/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */

/**
 * Tenancy module root (BackendPlan §3). Declared OPEN: the tenant-context primitives ({@code
 * context}) and the tenant-aware transaction boundary ({@code tx}) are shared infrastructure every
 * other module composes with, so all packages are module API.
 */
@org.springframework.modulith.ApplicationModule(
    type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.eip.tenancy;
