// "Report detail" — GET /api/v1/reports/{id} rendered as the composed document (M4, TASK-0022).
// Deterministic: every number here is read straight off the document (trends, friction,
// recommendations, in-flight) computed by the backend from recorded flow data — no AI narrative
// involved (the note below appears verbatim, matching the HTML export).
import { useMemo, useState } from 'react';
import type { EChartsOption, LineSeriesOption } from 'echarts';
import { apiGetText } from '../api/client';
import { useReport } from '../api/hooks';
import type { ReportDocument, ReportTeamSection, ReportTotals } from '../api/types';
import { useTenant } from '../app/tenantContext';
import { DARK_PALETTE, LIGHT_PALETTE } from '../lib/chartData';
import { hoursOneDecimal, isoDateOnly } from '../lib/format';
import { useECharts, usePrefersDark } from '../lib/useECharts';
import { RecommendationItem } from './RecommendationsCard';
import { EmptyState, ErrorState, Loading } from './states';

export function ReportDetail({
  reportId,
  onBack,
}: {
  reportId: string;
  onBack: () => void;
}): JSX.Element {
  const { tenantId } = useTenant();
  const query = useReport(tenantId, reportId, true);

  return (
    <section className="card card-report-detail span-all report-detail" aria-label="Report detail">
      <div className="report-detail-nav no-print">
        <button type="button" className="btn" onClick={onBack}>
          ← Back to reports
        </button>
      </div>

      {query.isLoading ? <Loading label="Loading report…" /> : null}
      {query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : null}
      {query.data !== undefined ? (
        <ReportBody reportId={reportId} tenantId={tenantId} document={query.data.document} />
      ) : null}
    </section>
  );
}

function ReportBody({
  reportId,
  tenantId,
  document: doc,
}: {
  reportId: string;
  tenantId: string;
  document: ReportDocument;
}): JSX.Element {
  const [exportError, setExportError] = useState<unknown>(null);
  const [opening, setOpening] = useState(false);

  // Anchors can't set the X-EIP-Tenant header, so the HTML export is fetched via the existing
  // client (respecting the tenant header) and opened as a Blob URL rather than a direct href.
  const handleOpenHtml = async (): Promise<void> => {
    setExportError(null);
    setOpening(true);
    try {
      const html = await apiGetText(
        `/api/v1/reports/${encodeURIComponent(reportId)}/html`,
        tenantId,
      );
      const blob = new Blob([html], { type: 'text/html' });
      const url = URL.createObjectURL(blob);
      window.open(url, '_blank');
    } catch (err) {
      setExportError(err);
    } finally {
      setOpening(false);
    }
  };

  return (
    <>
      <header className="report-header">
        <div>
          <h2>{doc.title}</h2>
          <p className="muted">
            {isoDateOnly(doc.periodStart)} → {isoDateOnly(doc.periodEnd)} · {doc.weeks} weeks ·
            generated {formatInstant(doc.generatedAt)} · metric {doc.metricVersion}
          </p>
          <p className="muted report-deterministic-note">
            Deterministic report — computed from recorded flow data; no AI involved.
          </p>
        </div>
        <div className="report-actions no-print">
          <button
            type="button"
            className="btn"
            disabled={opening}
            onClick={() => void handleOpenHtml()}
          >
            {opening ? 'Opening…' : 'Open HTML'}
          </button>
          <button type="button" className="btn" onClick={() => window.print()}>
            Print
          </button>
        </div>
      </header>

      {exportError !== null ? <ErrorState error={exportError} /> : null}

      <TotalsRow totals={doc.totals} />

      <div className="report-teams">
        {doc.teams.map((team) => (
          <TeamSection key={team.teamId} team={team} />
        ))}
      </div>
    </>
  );
}

function TotalsRow({ totals }: { totals: ReportTotals }): JSX.Element {
  return (
    <dl className="kv report-totals" aria-label="Report totals">
      <div>
        <dt>Teams reporting</dt>
        <dd>{totals.teamsReporting}</dd>
      </div>
      <div>
        <dt>Items resolved</dt>
        <dd>{totals.itemsResolved}</dd>
      </div>
      <div>
        <dt>Avg cycle time</dt>
        <dd>{hoursOneDecimal(totals.avgCycleSec)}</dd>
      </div>
      <div>
        <dt>P85 cycle time</dt>
        <dd>{hoursOneDecimal(totals.p85CycleSec)}</dd>
      </div>
      <div>
        <dt>Flow efficiency</dt>
        <dd>{Math.round(totals.flowEfficiencyPct)}%</dd>
      </div>
      <div>
        <dt>Blocked</dt>
        <dd>{Math.round(totals.blockedPct)}%</dd>
      </div>
      <div>
        <dt>Review wait</dt>
        <dd>{Math.round(totals.reviewWaitPct)}%</dd>
      </div>
      <div>
        <dt>Avg friction score</dt>
        <dd>{Math.round(totals.avgFrictionScore)}/100</dd>
      </div>
    </dl>
  );
}

// Human label for the dominant waiting sink (team-level, never an individual attribution) — mirrors
// FrictionCard's causeLabel so the same cause reads the same way everywhere in the product.
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

function TeamSection({ team }: { team: ReportTeamSection }): JSX.Element {
  return (
    <section className="team-section" aria-label={`${team.teamName} report section`}>
      <header className="team-section-head">
        <h3>{team.teamName}</h3>
        <span className="badge badge-friction" title="Friction score">
          {team.frictionScore}/100
        </span>
        <span className="muted">
          Dominant cause: <strong>{causeLabel(team.dominantCause)}</strong>
        </span>
      </header>

      <FrictionMiniChart teamName={team.teamName} points={team.points} />

      <div className="table-scroll">
        <table className="table">
          <thead>
            <tr>
              <th>Week</th>
              <th>Resolved</th>
              <th>Avg cycle</th>
              <th>P85 cycle</th>
              <th>Flow eff.</th>
              <th>Blocked</th>
              <th>Review wait</th>
              <th>Friction</th>
            </tr>
          </thead>
          <tbody>
            {team.points.map((p) => (
              <tr key={p.weekStart}>
                <td>{p.weekStart}</td>
                <td>{p.itemsResolved}</td>
                <td>{hoursOneDecimal(p.avgCycleSec)}</td>
                <td>{hoursOneDecimal(p.p85CycleSec)}</td>
                <td>{Math.round(p.flowEfficiencyPct)}%</td>
                <td>{Math.round(p.blockedPct)}%</td>
                <td>{Math.round(p.reviewWaitPct)}%</td>
                <td>{Math.round(p.frictionScore)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <p className="muted report-inflight-line">
        {team.inFlightCount} in flight
        {team.blockedInFlightCount > 0 ? <> · ⛔ {team.blockedInFlightCount} blocked</> : null}
      </p>

      {team.recommendations.length > 0 ? (
        <ul className="rec-list report-recommendations">
          {team.recommendations.map((rec) => (
            <RecommendationItem key={rec.code} rec={rec} />
          ))}
        </ul>
      ) : (
        <p className="muted">No recommendations for this team.</p>
      )}
    </section>
  );
}

// Reads the shared theme CSS variables so the chart matches light/dark mode (design-system rule).
function readMiniInk(isDark: boolean): { muted: string; grid: string } {
  const read = (varName: string, fallback: string): string => {
    if (typeof window === 'undefined' || typeof document === 'undefined') {
      return fallback;
    }
    const value = getComputedStyle(document.documentElement).getPropertyValue(varName).trim();
    return value.length > 0 ? value : fallback;
  };
  return {
    muted: read('--muted', isDark ? '#9aa5b6' : '#667085'),
    grid: 'rgba(128,128,128,0.12)',
  };
}

// Single-series friction trend per team — one axis, no legend (the section title above already
// names the series), theme-aware. Reuses the fixed categorical palette from chartData.ts so a
// team's line color matches its color on the Delivery trends board.
function FrictionMiniChart({
  teamName,
  points,
}: {
  teamName: string;
  points: ReportTeamSection['points'];
}): JSX.Element {
  const isDark = usePrefersDark();
  const palette = isDark ? DARK_PALETTE : LIGHT_PALETTE;
  const ink = readMiniInk(isDark);

  const option = useMemo<EChartsOption>(() => {
    const weeks = points.map((p) => p.weekStart);
    const series: LineSeriesOption = {
      type: 'line',
      name: `${teamName} friction score`,
      data: points.map((p) => p.frictionScore),
      color: palette[0],
      lineStyle: { width: 2, color: palette[0] },
      itemStyle: { color: palette[0] },
      showSymbol: true,
      symbolSize: 4,
      connectNulls: false,
    };
    return {
      backgroundColor: 'transparent',
      grid: { left: 36, right: 12, top: 12, bottom: 28 },
      legend: { show: false },
      tooltip: {
        trigger: 'axis',
        valueFormatter: (value) => (typeof value === 'number' ? `${Math.round(value)}` : '—'),
      },
      xAxis: {
        type: 'category',
        data: weeks,
        axisLabel: { color: ink.muted },
        axisLine: { lineStyle: { color: ink.muted } },
        splitLine: { show: false },
      },
      yAxis: {
        type: 'value',
        max: 100,
        axisLabel: { color: ink.muted },
        axisLine: { show: false },
        splitLine: { lineStyle: { color: ink.grid } },
      },
      series: [series],
    };
  }, [points, teamName, palette, ink.muted, ink.grid]);

  const containerRef = useECharts<HTMLDivElement>(option, isDark ? 'dark' : 'light');

  if (points.length === 0) {
    return <EmptyState title="Not enough history for a trend chart yet." />;
  }

  return (
    <div className="chart-card mini-chart">
      <div
        ref={containerRef}
        className="chart-canvas mini-chart-canvas"
        role="img"
        aria-label={`${teamName} friction score trend`}
      />
    </div>
  );
}

// Renders the ISO-8601 instant as a stable UTC date-time (locale-independent, en-US) — mirrors
// FrictionCard's formatComputedAt convention.
function formatInstant(iso: string): string {
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) {
    return iso;
  }
  return parsed.toISOString().replace('T', ' ').slice(0, 16) + ' UTC';
}
