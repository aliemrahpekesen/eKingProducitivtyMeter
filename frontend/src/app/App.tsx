import { useState } from 'react';
import { AdminPanel } from '../components/AdminPanel';
import { ConnectorList } from '../components/ConnectorList';
import { DemoBanner } from '../components/DemoBanner';
import { FrictionCard } from '../components/FrictionCard';
import { InFlightCard } from '../components/InFlightCard';
import { RecommendationsCard } from '../components/RecommendationsCard';
import { ReportsPanel } from '../components/ReportsPanel';
import { SessionCard } from '../components/SessionCard';
import { TenantBar } from '../components/TenantBar';
import { TrendsBoard } from '../components/TrendsBoard';

type Tab = 'overview' | 'reports' | 'admin';

function initialTab(): Tab {
  if (window.location.hash === '#admin') {
    return 'admin';
  }
  if (window.location.hash === '#reports') {
    return 'reports';
  }
  return 'overview';
}

// Application shell: Overview (the metric surface) + Reports (M4 deterministic reports) + Admin
// (tenant & integration management, M1). The installer deep-links to #admin so a fresh install
// lands on onboarding; #reports deep-links straight to the report library.
export function App(): JSX.Element {
  const [tab, setTab] = useState<Tab>(initialTab());

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
        <TenantBar />
      </header>

      <DemoBanner />

      <main className="app-main">
        {tab === 'overview' ? (
          <>
            <SessionCard />
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
