import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from './app/App';
import { TenantProvider } from './app/TenantProvider';
import './styles.css';

// P0-E5-S1 entry point: TanStack Query + the dev tenant provider around the app shell. OIDC login /
// RBAC-guarded routing / i18n follow in SPRINT-02+.
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
      <TenantProvider>
        <App />
      </TenantProvider>
    </QueryClientProvider>
  </StrictMode>,
);
