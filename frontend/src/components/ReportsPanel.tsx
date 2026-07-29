// "Reports" — deterministic period reports (M4, TASK-0022). Generates and lists exec-summary
// reports (POST/GET /api/v1/reports); selecting one opens ReportDetail full-screen (back button
// returns here). Every report is deterministic — computed from recorded flow data, no AI involved.
import { useState } from 'react';
import { useGenerateReport, useReports } from '../api/hooks';
import type { ReportView } from '../api/types';
import { useTenant } from '../app/tenantContext';
import { isoDateOnly } from '../lib/format';
import { ReportDetail } from './ReportDetail';
import { EmptyState, ErrorState, Loading } from './states';

const WEEK_OPTIONS = [4, 12, 26] as const;
type WeekOption = (typeof WEEK_OPTIONS)[number];
const DEFAULT_WEEKS: WeekOption = 12;

export function ReportsPanel(): JSX.Element {
  const { tenantId } = useTenant();
  const [weeks, setWeeks] = useState<WeekOption>(DEFAULT_WEEKS);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const query = useReports(tenantId);
  const generate = useGenerateReport(tenantId);

  if (selectedId !== null) {
    return <ReportDetail reportId={selectedId} onBack={() => setSelectedId(null)} />;
  }

  const reports = query.data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <section className="card card-reports span-all" aria-label="Reports">
      <header className="card-head">
        <div>
          <h2>Reports</h2>
          <span className="card-q">Deterministic period reports — no AI involved</span>
        </div>
        <div className="reports-toolbar">
          <label className="reports-period">
            Period
            <select
              value={weeks}
              onChange={(e) => setWeeks(Number(e.target.value) as WeekOption)}
              aria-label="Report period (weeks)"
            >
              {WEEK_OPTIONS.map((w) => (
                <option key={w} value={w}>
                  {w} weeks
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            className="btn primary"
            disabled={generate.isPending || tenantId.length === 0}
            onClick={() => generate.mutate({ weeks })}
          >
            {generate.isPending ? 'Generating…' : 'Generate report'}
          </button>
        </div>
      </header>

      {generate.isError ? <ErrorState error={generate.error} /> : null}

      {tenantId.length === 0 ? (
        <EmptyState title="No tenant selected" hint="Enter a tenant id above to begin." />
      ) : null}
      {tenantId.length > 0 && query.isLoading ? <Loading label="Loading reports…" /> : null}
      {tenantId.length > 0 && query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : null}
      {query.data !== undefined && reports.length === 0 ? (
        <EmptyState title="No reports yet — generate your first report" />
      ) : null}

      {reports.length > 0 ? (
        <>
          <ul className="admin-list reports-list">
            {reports.map((report) => (
              <ReportRow key={report.id} report={report} onOpen={() => setSelectedId(report.id)} />
            ))}
          </ul>
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

// Status is always shown as text + icon together — never color alone (accessibility rule). Any
// status string the backend returns renders honestly (unrecognized ones fall back to a neutral
// icon + the raw text) rather than guessing at a meaning the contract doesn't document.
function statusMeta(status: string): { icon: string; label: string } {
  switch (status) {
    case 'COMPLETED':
      return { icon: '✓', label: status };
    case 'FAILED':
      return { icon: '⛔', label: status };
    case 'PENDING':
    case 'RUNNING':
      return { icon: '…', label: status };
    default:
      return { icon: 'ℹ', label: status };
  }
}

function ReportRow({ report, onOpen }: { report: ReportView; onOpen: () => void }): JSX.Element {
  const status = statusMeta(report.status);
  return (
    <li className="admin-row report-row">
      <button type="button" className="report-row-btn" onClick={onOpen}>
        <span className="report-row-main">
          <strong>{report.title}</strong>{' '}
          <span className="muted mono">
            {isoDateOnly(report.periodStart)} → {isoDateOnly(report.periodEnd)} · {report.weeks}w
          </span>
          <span className="muted admin-id report-row-created">
            {formatCreatedAt(report.createdAt)}
          </span>
        </span>
        <span className={`badge badge-status status-${report.status.toLowerCase()}`}>
          {status.icon} {status.label}
        </span>
      </button>
    </li>
  );
}

// Renders the ISO-8601 creation instant as a stable UTC date-time (locale-independent, en-US).
function formatCreatedAt(iso: string): string {
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) {
    return iso;
  }
  return parsed.toISOString().replace('T', ' ').slice(0, 16) + ' UTC';
}
