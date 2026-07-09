import { ConnectorList } from '../components/ConnectorList';
import { DemoBanner } from '../components/DemoBanner';
import { FrictionCard } from '../components/FrictionCard';
import { SessionCard } from '../components/SessionCard';
import { TenantBar } from '../components/TenantBar';

// P0-E5-S1 application shell: the first customer-visible slice over the live /api/v1 read surface.
export function App(): JSX.Element {
  return (
    <div className="app">
      <header className="app-header">
        <div className="brand">
          <h1>Engineering Intelligence</h1>
          <p className="tagline">Where is engineering time lost?</p>
        </div>
        <TenantBar />
      </header>

      <DemoBanner />

      <main className="app-main">
        <SessionCard />
        <FrictionCard />
        <ConnectorList />
      </main>

      <footer className="app-footer muted">
        EIP · team-level insight only — no individual developer metrics (NFR-071) · v0.1 vertical
        slice
      </footer>
    </div>
  );
}
