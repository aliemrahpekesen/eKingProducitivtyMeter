/*
 * Copyright the Engineering Intelligence Platform (EIP) authors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.eip.app.application;

import com.eip.app.application.RunFrictionPipelineUseCase.PipelineResult;

/**
 * Loads the deterministic sample dataset for the CURRENT tenant from the admin panel: ensures the
 * demo team structure exists, then runs the real ingestion → normalization → friction pipeline.
 * Lets any freshly created tenant see a fully computed dashboard in one click — clearly labelled
 * SIMULATION data.
 */
public interface LoadSampleDataUseCase {

  /**
   * Ensures sample structure and computes friction for the current tenant.
   *
   * @return the pipeline outcome
   */
  PipelineResult load();
}
