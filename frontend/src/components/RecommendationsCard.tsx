// "Recommendations" — GET /api/v1/insights/recommendations. Team-level only (NFR-071): every
// recommendation is grouped by team, never by individual. Rule-based v0.1 (deterministic, not an
// LLM guess) — every recommendation cites the metric numbers it fired on, so it reads honestly.
import { useRecommendations } from '../api/hooks';
import type { RecommendationView, TeamRecommendationsView } from '../api/types';
import { useTenant } from '../app/tenantContext';
import { EmptyState, ErrorState, Loading } from './states';

export function RecommendationsCard(): JSX.Element {
  const { tenantId } = useTenant();
  const query = useRecommendations(tenantId);

  return (
    <section className="card card-recommendations" aria-label="Recommendations">
      <header className="card-head">
        <div>
          <h2>Recommendations</h2>
          <span className="card-q">What should each team do next?</span>
        </div>
      </header>

      {tenantId.length === 0 ? (
        <EmptyState title="No tenant selected" hint="Enter a tenant id above to begin." />
      ) : null}
      {tenantId.length > 0 && query.isLoading ? <Loading label="Loading recommendations…" /> : null}
      {tenantId.length > 0 && query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : null}
      {query.data !== undefined ? <RecommendationsBody teams={query.data} /> : null}
    </section>
  );
}

function RecommendationsBody({ teams }: { teams: TeamRecommendationsView[] }): JSX.Element {
  const total = teams.reduce((sum, t) => sum + t.recommendations.length, 0);
  if (total === 0) {
    return <EmptyState title="No recommendations — teams look healthy." />;
  }

  return (
    <>
      <ul className="rec-teams">
        {teams
          .filter((team) => team.recommendations.length > 0)
          .map((team) => (
            <li key={team.teamId} className="rec-team">
              <div className="rec-team-head">
                <span className="team-name">{team.teamName}</span>
                <span className="badge badge-friction" title="Friction score">
                  {team.frictionScore}/100
                </span>
              </div>
              <ul className="rec-list">
                {team.recommendations.map((rec) => (
                  <RecommendationItem key={rec.code} rec={rec} />
                ))}
              </ul>
            </li>
          ))}
      </ul>
      <p className="muted rec-footer">
        Rule-based &amp; deterministic (recommendations v0.1) — every recommendation cites its
        numbers.
      </p>
    </>
  );
}

// Severity is always shown as text + icon together — never color alone (accessibility rule).
function severityMeta(severity: RecommendationView['severity']): {
  icon: string;
  className: string;
} {
  switch (severity) {
    case 'CRITICAL':
      return { icon: '⛔', className: 'sev-critical' };
    case 'WARN':
      return { icon: '⚠', className: 'sev-warn' };
    default:
      return { icon: 'ℹ', className: 'sev-info' };
  }
}

function RecommendationItem({ rec }: { rec: RecommendationView }): JSX.Element {
  const sev = severityMeta(rec.severity);
  return (
    <li className="rec-item">
      <div className="rec-item-head">
        <span className={`sev-chip ${sev.className}`}>
          {sev.icon} {rec.severity}
        </span>
        <strong>{rec.title}</strong>
      </div>
      <p className="rec-rationale">{rec.rationale}</p>
      {rec.actions.length > 0 ? (
        <ul className="rec-actions">
          {rec.actions.map((action, index) => (
            <li key={`${rec.code}-${index}`}>{action}</li>
          ))}
        </ul>
      ) : null}
      {rec.metricRefs.length > 0 ? (
        <p className="mono muted rec-refs">{rec.metricRefs.join(' · ')}</p>
      ) : null}
    </li>
  );
}
