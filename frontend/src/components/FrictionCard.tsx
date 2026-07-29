import { useState } from 'react';
import { useFrictionEvidence, useFrictionSummary } from '../api/hooks';
import type { FrictionMetricView, FrictionSummaryView, WorkItemEvidenceView } from '../api/types';
import { useTenant } from '../app/tenantContext';
import { ageDays } from '../lib/format';
import { DemoBadge } from './DemoBadge';
import { EmptyState, ErrorState, Loading } from './states';

// "Where is engineering time lost?" — the hero metric from GET /api/v1/friction/summary.
// Team-level only (no individual data, NFR-071); deterministic; computed from ingested + normalized
// + correlated flow data (not seed rows). The metric's own definition, caveats, and gaming risks are
// surfaced so the number is read honestly (FEAT-031). Each team drills to its correlation evidence.
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
      {query.data !== undefined ? <FrictionBody data={query.data} tenantId={tenantId} /> : null}
    </section>
  );
}

// Human label for the dominant waiting sink (team-level, never an individual attribution).
function causeLabel(cause: string): string {
  switch (cause) {
    case 'BLOCKED':
      return 'blocked time';
    case 'REVIEW_WAIT':
      return 'review wait';
    default:
      return 'none';
  }
}

function FrictionBody({
  data,
  tenantId,
}: {
  data: FrictionSummaryView;
  tenantId: string;
}): JSX.Element {
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
            Top bottleneck: <strong>{worst.teamName}</strong> — mostly{' '}
            {causeLabel(worst.dominantCause)}
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
              {team.workItems} items · active {team.flowEfficiencyPct}% · blocked {team.blockedPct}%
              · review wait {team.reviewWaitPct}% · rework {team.reworkCount} ·{' '}
              <strong>{causeLabel(team.dominantCause)}</strong>
            </div>
            <TeamEvidenceDrawer tenantId={tenantId} teamId={team.teamId} teamName={team.teamName} />
          </li>
        ))}
      </ol>

      <p className="muted friction-meta">
        {data.metricVersion !== undefined ? <>Metric {data.metricVersion}</> : null}
        {data.computedAt !== undefined ? (
          <> · computed {formatComputedAt(data.computedAt)}</>
        ) : null}
        {data.simulation ? <> · simulation data</> : null}
      </p>

      {data.metric !== undefined ? <MetricExplainer metric={data.metric} /> : null}
    </>
  );
}

// A per-team drill-to-evidence drawer. Fetches the team's correlated artifacts only when opened.
function TeamEvidenceDrawer({
  tenantId,
  teamId,
  teamName,
}: {
  tenantId: string;
  teamId: string;
  teamName: string;
}): JSX.Element {
  const [open, setOpen] = useState(false);
  return (
    <details
      className="evidence"
      onToggle={(e) => setOpen((e.currentTarget as HTMLDetailsElement).open)}
    >
      <summary>Show evidence for {teamName}</summary>
      {open ? <TeamEvidence tenantId={tenantId} teamId={teamId} /> : null}
    </details>
  );
}

function TeamEvidence({ tenantId, teamId }: { tenantId: string; teamId: string }): JSX.Element {
  const query = useFrictionEvidence(tenantId, teamId, true);
  if (query.isLoading) {
    return <Loading label="Loading evidence…" />;
  }
  if (query.isError) {
    return <ErrorState error={query.error} onRetry={() => void query.refetch()} />;
  }
  const items = query.data?.items ?? [];
  if (items.length === 0) {
    return <EmptyState title="No evidence for this team" />;
  }
  return (
    <ul className="evidence-list">
      {items.map((item) => (
        <EvidenceItem key={item.workItemKey ?? item.title} item={item} />
      ))}
    </ul>
  );
}

function EvidenceItem({ item }: { item: WorkItemEvidenceView }): JSX.Element {
  const artifacts = [
    item.pullRequestKey,
    item.buildKey !== undefined ? `${item.buildKey} (${item.buildStatus ?? '—'})` : undefined,
    item.qualityGateKey !== undefined
      ? `${item.qualityGateKey} (${item.qualityGateStatus ?? '—'})`
      : undefined,
  ].filter((a): a is string => a !== undefined);

  return (
    <li className="evidence-item">
      <div className="evidence-head">
        <span className="mono">{item.workItemKey ?? '—'}</span> {item.title}
      </div>
      <div className="muted">
        cycle {ageDays(item.cycleTimeSec)} · active {ageDays(item.activeSec)} · blocked{' '}
        {ageDays(item.blockedSec)} · review {ageDays(item.reviewWaitSec)} · rework{' '}
        {item.reworkCount}
      </div>
      {artifacts.length > 0 ? <div className="muted mono">{artifacts.join(' · ')}</div> : null}
      <div className="muted evidence-timeline">
        {item.transitions.map((t) => `${t.fromState ?? '·'}→${t.toState}`).join('  ')}
      </div>
    </li>
  );
}

// Renders the ISO-8601 computation instant as a stable UTC date-time (locale-independent, en-US).
function formatComputedAt(iso: string): string {
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) {
    return iso;
  }
  return parsed.toISOString().replace('T', ' ').slice(0, 16) + ' UTC';
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
