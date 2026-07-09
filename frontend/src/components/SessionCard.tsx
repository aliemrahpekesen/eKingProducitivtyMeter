import { useSession } from '../api/hooks';
import { useTenant } from '../app/tenantContext';
import { EmptyState, ErrorState, Loading } from './states';

// "Which tenant / session am I viewing?" — the current tenant identity from GET /api/v1/session.
export function SessionCard(): JSX.Element {
  const { tenantId } = useTenant();
  const query = useSession(tenantId);

  return (
    <section className="card card-session" aria-label="Session">
      <header className="card-head">
        <h2>Session</h2>
        <span className="card-q">Which tenant / session am I viewing?</span>
      </header>

      {tenantId.length === 0 ? (
        <EmptyState title="No tenant selected" hint="Enter a tenant id above to begin." />
      ) : null}
      {tenantId.length > 0 && query.isLoading ? <Loading label="Loading session…" /> : null}
      {tenantId.length > 0 && query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : null}
      {query.data !== undefined ? (
        <dl className="kv">
          <div>
            <dt>Status</dt>
            <dd>
              <span className="badge badge-ok">Active</span>
            </dd>
          </div>
          <div>
            <dt>Tenant</dt>
            <dd className="mono">{query.data.tenantId}</dd>
          </div>
          <div>
            <dt>Organization</dt>
            <dd>{query.data.organizationName ?? <span className="muted">— none seeded</span>}</dd>
          </div>
        </dl>
      ) : null}
    </section>
  );
}
