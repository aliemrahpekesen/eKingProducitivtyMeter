import { useConnectors } from '../api/hooks';
import { useTenant } from '../app/tenantContext';
import { DemoBadge } from './DemoBadge';
import { EmptyState, ErrorState, Loading } from './states';

// "Which connectors are connected?" — the tenant's connector registry from the cursor-paginated
// GET /api/v1/connectors. The contract exposes id/type/name/status/simulation only (no last-sync /
// checkpoint field), so those are the only columns shown — nothing is invented.
export function ConnectorList(): JSX.Element {
  const { tenantId } = useTenant();
  const query = useConnectors(tenantId);
  const connectors = query.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <section className="card card-connectors" aria-label="Connectors">
      <header className="card-head">
        <h2>Connectors</h2>
        <span className="card-q">Which connectors are connected?</span>
      </header>

      {tenantId.length === 0 ? (
        <EmptyState title="No tenant selected" hint="Enter a tenant id above to begin." />
      ) : null}
      {tenantId.length > 0 && query.isLoading ? <Loading label="Loading connectors…" /> : null}
      {tenantId.length > 0 && query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : null}
      {query.data !== undefined && connectors.length === 0 ? (
        <EmptyState
          title="No connectors registered"
          hint="Register a connector (simulation) to see it here."
        />
      ) : null}

      {connectors.length > 0 ? (
        <>
          <table className="table">
            <thead>
              <tr>
                <th>Type</th>
                <th>Name</th>
                <th>Status</th>
                <th>Data</th>
              </tr>
            </thead>
            <tbody>
              {connectors.map((connector) => (
                <tr key={connector.id}>
                  <td className="mono">{connector.type}</td>
                  <td>{connector.name}</td>
                  <td>
                    <span className={`badge badge-status status-${connector.status.toLowerCase()}`}>
                      {connector.status}
                    </span>
                  </td>
                  <td>
                    {connector.simulation ? (
                      <DemoBadge />
                    ) : (
                      <span className="badge badge-live">LIVE</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {query.hasNextPage ? (
            <button
              type="button"
              className="btn"
              disabled={query.isFetchingNextPage}
              onClick={() => void query.fetchNextPage()}
            >
              {query.isFetchingNextPage ? 'Loading…' : 'Load more'}
            </button>
          ) : null}
        </>
      ) : null}
    </section>
  );
}
