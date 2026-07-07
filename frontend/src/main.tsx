import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './app/App';

// Scaffold entry point. The real app shell (OIDC login, tenant switcher, RBAC-guarded routing,
// TanStack Query, i18n) lands in P0-E5-S1 (SPRINT-03) per FrontendPlan.md §2.
const rootElement = document.getElementById('root');
if (rootElement === null) {
  throw new Error('Root element #root not found');
}

createRoot(rootElement).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
