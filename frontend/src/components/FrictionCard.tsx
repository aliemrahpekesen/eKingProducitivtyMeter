import { useFrictionSummary } from '../api/hooks';
import type { FrictionMetricView, FrictionSummaryView } from '../api/types';
import { useTenant } from '../app/tenantContext';
import { ageDays } from '../lib/format';
import { DemoBadge } from './DemoBadge';
import { EmptyState, ErrorState, Loading } from './states';

// "Where is engineering time lost?" — the hero metric from GET /api/v1/friction/summary.
// Team-level only (no individual data, NFR-071); deterministic; the metric's own definition,
// caveats, and gaming risks are surfaced so the number is read honestly (FEAT-031).
export function FrictionCard(): JSX.Element {
  const { tenantId } = useTenant();
  const query = useFrictionSummary(tenantId);

  return (
    <section className="card card-friction" aria-label="Engineering Friction">
      <header className="card-head">
        <div>
          <h2>
            Engineering Friction <DemoBadge label="SIMULATION DATA" />
          </h2>
          <span className="card-q">Where is engineering time lost?</span>
        </div>
      </header>

      {tenantId.length === 0 ? (
        <EmptyState title="No tenant selected" hint="Enter a tenant id above to begin." />
      ) : null}
      {tenantId.length > 0 && query.isLoading ? <Loading label="Computing friction…" /> : null}
      {tenantId.length > 0 && query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : null}
      {query.data !== undefined ? <FrictionBody data={query.data} /> : null}
    </section>
  );
}

function FrictionBody({ data }: { data: FrictionSummaryView }): JSX.Element {
  const teams = data.teams; // backend-sorted worst-first
  if (teams.length === 0) {
    return (
      <EmptyState
        title="No team friction data yet"
        hint="Seed simulation data (demo profile) to populate this card."
      />
    );
  }
  const worst = teams[0];

  return (
    <>
      <div className="hero">
        <div className="hero-score" aria-label="Top friction score">
          <span className="hero-number">{worst.frictionScore}</span>
          <span className="hero-max">/100</span>
        </div>
        <div className="hero-caption">
          <p className="hero-team">
            Top bottleneck: <strong>{worst.teamName}</strong>
          </p>
          <p className="muted">
            {data.teamsReporting} team{data.teamsReporting === 1 ? '' : 's'} reporting · team-level,
            deterministic
          </p>
        </div>
      </div>

      <ol className="team-list">
        {teams.map((team) => (
          <li key={team.teamId} className="team-row">
            <div className="team-main">
              <span className="team-name">{team.teamName}</span>
              <span className="team-score">{team.frictionScore}</span>
            </div>
            <div className="bar" aria-hidden="true">
              <span className="bar-fill" style={{ width: `${team.frictionScore}%` }} />
            </div>
            <div className="team-signals muted">
              WIP {team.wip} · breaches {team.wipLimitBreaches} · oldest{' '}
              {ageDays(team.oldestInProgressAgeSec)} · review queue {team.reviewQueueDepth}
            </div>
          </li>
        ))}
      </ol>

      {data.metric !== null ? <MetricExplainer metric={data.metric} /> : null}
    </>
  );
}

function MetricExplainer({ metric }: { metric: FrictionMetricView }): JSX.Element {
  return (
    <details className="explainer">
      <summary>How “{metric.name}” is measured — and how it can mislead</summary>
      <dl className="kv">
        <div>
          <dt>Purpose</dt>
          <dd>{metric.purpose}</dd>
        </div>
        <div>
          <dt>Formula</dt>
          <dd className="mono">{metric.formula}</dd>
        </div>
        <div>
          <dt>Grain</dt>
          <dd>{metric.grain}</dd>
        </div>
        <div>
          <dt>Caveats</dt>
          <dd>{metric.caveats}</dd>
        </div>
        <div>
          <dt>Gaming risks</dt>
          <dd>{metric.gamingRisks}</dd>
        </div>
      </dl>
    </details>
  );
}
