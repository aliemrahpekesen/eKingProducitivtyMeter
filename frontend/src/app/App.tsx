import { useState } from 'react';
import { AdminPanel } from '../components/AdminPanel';
import { ConnectorList } from '../components/ConnectorList';
import { DemoBanner } from '../components/DemoBanner';
import { FrictionCard } from '../components/FrictionCard';
import { InFlightCard } from '../components/InFlightCard';
import { RecommendationsCard } from '../components/RecommendationsCard';
import { SessionCard } from '../components/SessionCard';
import { TenantBar } from '../components/TenantBar';
import { TrendsBoard } from '../components/TrendsBoard';

type Tab = 'overview' | 'admin';

// Application shell: Overview (the metric surface) + Admin (tenant & integration management, M1).
// The installer deep-links to #admin so a fresh install lands on onboarding.
export function App(): JSX.Element {
  const [tab, setTab] = useState<Tab>(window.location.hash === '#admin' ? 'admin' : 'overview');

  const switchTo = (next: Tab): void => {
    setTab(next);
    window.location.hash = next === 'admin' ? '#admin' : '';
  };

  return (
    <div className="app">
      <header className="app-header">
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
        ) : (
          <AdminPanel />
        )}
      </main>

      <footer className="app-footer muted">
        EIP · team-level insight only — no individual developer metrics (NFR-071) · v0.1 vertical
        slice
      </footer>
    </div>
  );
}
