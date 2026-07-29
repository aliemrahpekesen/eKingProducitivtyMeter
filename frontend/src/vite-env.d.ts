/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Dev/demo tenant id sent as the `X-EIP-Tenant` header (replaced by OIDC in SPRINT-02). */
  readonly VITE_EIP_TENANT_ID?: string;
  /** API base URL; empty (default) uses same-origin + the Vite dev proxy. */
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
