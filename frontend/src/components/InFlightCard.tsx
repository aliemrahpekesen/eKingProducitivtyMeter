// "Work in progress" — GET /api/v1/metrics/in-flight. Team-level tables of in-flight work items
// (no individual assignee data, NFR-071); the backend already sorts rows by age descending.
import { useInFlight } from '../api/hooks';
import type { InFlightItemView, TeamInFlightView } from '../api/types';
import { useTenant } from '../app/tenantContext';
import { ageDays } from '../lib/format';
import { EmptyState, ErrorState, Loading } from './states';

export function InFlightCard(): JSX.Element {
  const { tenantId } = useTenant();
  const query = useInFlight(tenantId);

  return (
    <section className="card card-in-flight" aria-label="Work in progress">
      <header className="card-head">
        <div>
          <h2>Work in progress</h2>
          <span className="card-q">What&rsquo;s aging in flight right now?</span>
        </div>
      </header>

      {tenantId.length === 0 ? (
        <EmptyState title="No tenant selected" hint="Enter a tenant id above to begin." />
      ) : null}
      {tenantId.length > 0 && query.isLoading ? <Loading label="Loading in-flight work…" /> : null}
      {tenantId.length > 0 && query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : null}
      {query.data !== undefined ? <InFlightBody teams={query.data} /> : null}
    </section>
  );
}

function InFlightBody({ teams }: { teams: TeamInFlightView[] }): JSX.Element {
  const totalItems = teams.reduce((sum, t) => sum + t.items.length, 0);
  if (totalItems === 0) {
    return <EmptyState title="Nothing in flight." />;
  }

  return (
    <div className="in-flight-teams">
      {teams
        .filter((team) => team.items.length > 0)
        .map((team) => (
          <div key={team.teamId} className="in-flight-team">
            <h3 className="in-flight-team-name">{team.teamName}</h3>
            <div className="table-scroll">
              <table className="table">
                <thead>
                  <tr>
                    <th>Key</th>
                    <th>Title</th>
                    <th>State</th>
                    <th>Age</th>
                  </tr>
                </thead>
                <tbody>
                  {team.items.map((item) => (
                    <InFlightRow key={item.workItemKey} item={item} />
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        ))}
    </div>
  );
}

function InFlightRow({ item }: { item: InFlightItemView }): JSX.Element {
  return (
    <tr>
      <td className="mono">{item.workItemKey}</td>
      <td>
        {item.title}
        {item.blocked ? <span className="badge badge-blocked">⛔ BLOCKED</span> : null}
      </td>
      <td>{item.state}</td>
      <td>{ageDays(item.ageSec)}</td>
    </tr>
  );
}
