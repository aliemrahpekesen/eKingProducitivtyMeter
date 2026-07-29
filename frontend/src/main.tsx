import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from './app/App';
import { TenantProvider } from './app/TenantProvider';
import { AuthProvider } from './auth/AuthProvider';
import './styles.css';

// P0-E5-S1 entry point, now OIDC-aware (M5 Wave S1b): <AuthProvider> fetches GET
// /api/v1/session/auth and either renders the app unchanged (HEADER mode) or gates it behind a
// Keycloak Authorization Code + PKCE login (OIDC mode). RBAC-guarded routing / i18n follow later.
const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, refetchOnWindowFocus: false },
  },
});

const rootElement = document.getElementById('root');
if (rootElement === null) {
  throw new Error('Root element #root not found');
}

createRoot(rootElement).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <TenantProvider>
          <App />
        </TenantProvider>
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>,
);
