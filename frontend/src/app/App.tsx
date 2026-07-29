import { useEffect, useState } from 'react';
import { useSession } from '../api/hooks';
import { useAuth, type AuthMode } from '../auth/authContext';
import { AdminPanel } from '../components/AdminPanel';
import { AiExplainCard } from '../components/AiExplainCard';
import { ConnectorList } from '../components/ConnectorList';
import { DemoBanner } from '../components/DemoBanner';
import { FrictionCard } from '../components/FrictionCard';
import { InFlightCard } from '../components/InFlightCard';
import { RecommendationsCard } from '../components/RecommendationsCard';
import { ReportsPanel } from '../components/ReportsPanel';
import { SessionCard } from '../components/SessionCard';
import { TenantBar } from '../components/TenantBar';
import { TrendsBoard } from '../components/TrendsBoard';
import { UserChip } from '../components/UserChip';
import { useTenant } from './tenantContext';

type Tab = 'overview' | 'reports' | 'admin';

// Any non-empty value works: in OIDC mode the backend derives the tenant from the access token's
// claim and client.ts never sends X-EIP-Tenant, so this only exists to satisfy useSession's
// `enabled` gate until the real tenantId (from the /session response) replaces it below.
const OIDC_SESSION_PROBE_TENANT_ID = '__oidc__';

function initialTab(): Tab {
  if (window.location.hash === '#admin') {
    return 'admin';
  }
  if (window.location.hash === '#reports') {
    return 'reports';
  }
  return 'overview';
}

// OIDC mode has no manual tenant entry (TenantBar): the effective tenantId is resolved from
// GET /api/v1/session and fed into the same TenantContext every other component already reads, so
// the rest of the app (FrictionCard, TrendsBoard, AdminPanel, …) needs no changes at all.
function useResolveOidcTenant(mode: AuthMode): void {
  const { tenantId, setTenantId } = useTenant();
  const probe = useSession(mode === 'OIDC' ? OIDC_SESSION_PROBE_TENANT_ID : '');

  useEffect(() => {
    if (mode === 'OIDC' && probe.data !== undefined && probe.data.tenantId !== tenantId) {
      setTenantId(probe.data.tenantId);
    }
  }, [mode, probe.data, tenantId, setTenantId]);
}

// Application shell: Overview (the metric surface) + Reports (M4 deterministic reports) + Admin
// (tenant & integration management, M1). The installer deep-links to #admin so a fresh install
// lands on onboarding; #reports deep-links straight to the report library.
export function App(): JSX.Element {
  const [tab, setTab] = useState<Tab>(initialTab());
  const auth = useAuth();
  useResolveOidcTenant(auth.mode);

  const switchTo = (next: Tab): void => {
    setTab(next);
    window.location.hash = next === 'overview' ? '' : `#${next}`;
  };

  return (
    <div className="app">
      <header className="app-header no-print">
        <div className="brand">
          <h1>Engineering Intelligence</h1>
          <p className="tagline">Where is engineering time lost?</p>
        </div>
        <nav className="tabs" aria-label="Sections">
          <button
            type="button"
            className={tab === 'overview' ? 'tab active' : 'tab'}
            onClick={() => switchTo('overview')}
          >
            Overview
          </button>
          <button
            type="button"
            className={tab === 'reports' ? 'tab active' : 'tab'}
            onClick={() => switchTo('reports')}
          >
            Reports
          </button>
          <button
            type="button"
            className={tab === 'admin' ? 'tab active' : 'tab'}
            onClick={() => switchTo('admin')}
          >
            Admin
          </button>
        </nav>
        {auth.mode === 'HEADER' ? <TenantBar /> : <UserChip />}
      </header>

      <DemoBanner />

      <main className="app-main">
        {tab === 'overview' ? (
          <>
            <SessionCard />
            <AiExplainCard />
            <FrictionCard />
            <TrendsBoard />
            <RecommendationsCard />
            <InFlightCard />
            <ConnectorList />
          </>
        ) : tab === 'reports' ? (
          <ReportsPanel />
        ) : (
          <AdminPanel />
        )}
      </main>

      <footer className="app-footer muted no-print">
        EIP · team-level insight only — no individual developer metrics (NFR-071) · v0.1 vertical
        slice
      </footer>
    </div>
  );
}
