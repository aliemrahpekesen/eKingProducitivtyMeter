// "Delivery trends" — GET /api/v1/metrics/trends rendered as ECharts line/bar charts. Team-level
// only (NFR-071): every series is a team aggregate, never an individual. Colors are assigned once
// per fixed alphabetical team order (design-system rule) so the same team is the same color in
// every chart; teams beyond the top 4 by latest friction score fold into one gray "Other" series.
import { useMemo, useState } from 'react';
import type { EChartsOption, BarSeriesOption, LineSeriesOption } from 'echarts';
import { useMetricTrends } from '../api/hooks';
import type { TrendsView } from '../api/types';
import { useTenant } from '../app/tenantContext';
import { ageDays } from '../lib/format';
import {
  DARK_PALETTE,
  LIGHT_PALETTE,
  assignTeamColors,
  foldTeams,
  toCycleP85Series,
  toCycleSeries,
  toFlowEffSeries,
  toFrictionSeries,
  toThroughputSeries,
  type ChartData,
  type ChartSeries,
  type TeamIdentity,
} from '../lib/chartData';
import { useECharts, usePrefersDark } from '../lib/useECharts';
import { EmptyState, ErrorState, Loading } from './states';

const RANGE_OPTIONS = [4, 12, 26] as const;
type RangeWeeks = (typeof RANGE_OPTIONS)[number];
const DEFAULT_RANGE: RangeWeeks = 12;

export function TrendsBoard(): JSX.Element {
  const { tenantId } = useTenant();
  const [weeks, setWeeks] = useState<RangeWeeks>(DEFAULT_RANGE);
  const query = useMetricTrends(tenantId, weeks);

  return (
    <section className="card card-trends span-all" aria-label="Delivery trends">
      <header className="card-head">
        <div>
          <h2>Delivery trends</h2>
          <span className="card-q">How is delivery changing over time?</span>
        </div>
        <div className="range-selector" role="group" aria-label="Trend range">
          {RANGE_OPTIONS.map((w) => (
            <button
              key={w}
              type="button"
              className={weeks === w ? 'tab active' : 'tab'}
              onClick={() => setWeeks(w)}
            >
              {w}w
            </button>
          ))}
        </div>
      </header>

      {tenantId.length === 0 ? (
        <EmptyState title="No tenant selected" hint="Enter a tenant id above to begin." />
      ) : null}
      {tenantId.length > 0 && query.isLoading ? <Loading label="Loading trends…" /> : null}
      {tenantId.length > 0 && query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : null}
      {query.data !== undefined ? <TrendsBody data={query.data} /> : null}
    </section>
  );
}

function TrendsBody({ data }: { data: TrendsView }): JSX.Element {
  const isDark = usePrefersDark();

  const { kept, other } = useMemo(() => foldTeams(data.teams, 4), [data.teams]);
  const foldedCount = data.teams.length - kept.length;
  const chartTeams = other !== null ? [...kept, other] : kept;

  const colors = useMemo(() => {
    const identities: TeamIdentity[] = kept.map((t) => ({
      teamId: t.teamId,
      teamName: t.teamName,
    }));
    return assignTeamColors(identities, isDark ? DARK_PALETTE : LIGHT_PALETTE);
  }, [kept, isDark]);

  if (data.teams.length === 0 || chartTeams.every((t) => t.points.length === 0)) {
    return <EmptyState title="Not enough history yet — trends fill in as syncs accumulate." />;
  }

  const friction = toFrictionSeries(chartTeams, colors);
  const throughput = toThroughputSeries(chartTeams, colors);
  const cycle = toCycleSeries(chartTeams, colors);
  const cycleP85 = chartTeams.length <= 2 ? toCycleP85Series(chartTeams, colors) : null;
  const flowEff = toFlowEffSeries(chartTeams, colors);

  return (
    <>
      {foldedCount > 0 ? (
        <p className="muted trends-fold-note">
          {foldedCount} more team{foldedCount === 1 ? '' : 's'} folded into Other
        </p>
      ) : null}
      <div className="chart-grid">
        <LineChartPanel
          title="Friction score"
          caption="Composite friction (0–100) per team, worst weeks stand out."
          data={friction}
          isDark={isDark}
          valueFormatter={(v) => `${Math.round(v)}`}
          yAxisMax={100}
        />
        <BarChartPanel
          title="Throughput"
          caption="Items resolved per week, per team."
          data={throughput}
          isDark={isDark}
          valueFormatter={(v) => `${Math.round(v)}`}
        />
        <LineChartPanel
          title="Cycle time"
          caption={
            cycleP85 !== null
              ? 'Average cycle time (solid) and p85 (dashed) per team.'
              : 'Average cycle time per team — p85 shown only when ≤2 teams are displayed.'
          }
          data={cycle}
          extra={cycleP85?.series}
          isDark={isDark}
          valueFormatter={(v) => ageDays(v)}
        />
        <LineChartPanel
          title="Flow efficiency"
          caption="Share of cycle time spent actively worked (not waiting/blocked), per team."
          data={flowEff}
          isDark={isDark}
          valueFormatter={(v) => `${Math.round(v)}%`}
          yAxisMax={100}
        />
      </div>
    </>
  );
}

// ── chart panels ────────────────────────────────────────────────────────────────────────────────

interface InkColors {
  text: string;
  muted: string;
  grid: string;
}

function readInkColors(isDark: boolean): InkColors {
  const read = (varName: string, fallback: string): string => {
    if (typeof window === 'undefined' || typeof document === 'undefined') {
      return fallback;
    }
    const value = getComputedStyle(document.documentElement).getPropertyValue(varName).trim();
    return value.length > 0 ? value : fallback;
  };
  return {
    text: read('--text', isDark ? '#e7ebf3' : '#1c2230'),
    muted: read('--muted', isDark ? '#9aa5b6' : '#667085'),
    grid: 'rgba(128,128,128,0.12)',
  };
}

function LineChartPanel({
  title,
  caption,
  data,
  extra,
  isDark,
  valueFormatter,
  yAxisMax,
}: {
  title: string;
  caption: string;
  data: ChartData;
  extra?: ChartSeries[];
  isDark: boolean;
  valueFormatter: (v: number) => string;
  yAxisMax?: number;
}): JSX.Element {
  const option = useMemo(
    () => buildLineOption(data, extra ?? [], readInkColors(isDark), valueFormatter, yAxisMax),
    [data, extra, isDark, valueFormatter, yAxisMax],
  );
  const containerRef = useECharts<HTMLDivElement>(option, isDark ? 'dark' : 'light');

  return (
    <div className="chart-card">
      <h3>{title}</h3>
      <p className="muted chart-caption">{caption}</p>
      <div ref={containerRef} className="chart-canvas" role="img" aria-label={title} />
    </div>
  );
}

function BarChartPanel({
  title,
  caption,
  data,
  isDark,
  valueFormatter,
}: {
  title: string;
  caption: string;
  data: ChartData;
  isDark: boolean;
  valueFormatter: (v: number) => string;
}): JSX.Element {
  const option = useMemo(
    () => buildBarOption(data, readInkColors(isDark), valueFormatter),
    [data, isDark, valueFormatter],
  );
  const containerRef = useECharts<HTMLDivElement>(option, isDark ? 'dark' : 'light');

  return (
    <div className="chart-card">
      <h3>{title}</h3>
      <p className="muted chart-caption">{caption}</p>
      <div ref={containerRef} className="chart-canvas" role="img" aria-label={title} />
    </div>
  );
}

function buildLineOption(
  data: ChartData,
  extra: ChartSeries[],
  ink: InkColors,
  valueFormatter: (v: number) => string,
  yAxisMax?: number,
): EChartsOption {
  const primaryCount = data.series.length;
  const showLegend = primaryCount >= 2;
  const showEndLabels = primaryCount >= 1 && primaryCount <= 4;

  const toLineSeries = (series: ChartSeries, dashed: boolean): LineSeriesOption => ({
    type: 'line',
    name: dashed ? `${series.name} (p85)` : series.name,
    data: series.data,
    color: series.color,
    lineStyle: { width: 2, type: dashed ? 'dashed' : 'solid', color: series.color },
    itemStyle: { color: series.color },
    showSymbol: true,
    symbolSize: 5,
    connectNulls: false,
    endLabel: showEndLabels
      ? {
          show: true,
          formatter: () => (dashed ? `${series.name} p85` : series.name),
          color: ink.text,
        }
      : undefined,
  });

  const series: LineSeriesOption[] = [
    ...data.series.map((s) => toLineSeries(s, false)),
    ...extra.map((s) => toLineSeries(s, true)),
  ];

  return {
    backgroundColor: 'transparent',
    textStyle: { color: ink.text },
    grid: { left: 44, right: showEndLabels ? 96 : 24, top: 20, bottom: 32 },
    legend: showLegend ? { show: true, top: 0, textStyle: { color: ink.text } } : { show: false },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'line' },
      valueFormatter: (value) => (typeof value === 'number' ? valueFormatter(value) : '—'),
    },
    xAxis: {
      type: 'category',
      data: data.weeks,
      axisLine: { lineStyle: { color: ink.muted } },
      axisLabel: { color: ink.muted },
      splitLine: { show: false },
    },
    yAxis: {
      type: 'value',
      max: yAxisMax,
      axisLine: { show: false },
      axisLabel: { color: ink.muted, formatter: (value: number) => valueFormatter(value) },
      splitLine: { lineStyle: { color: ink.grid } },
    },
    series,
  };
}

function buildBarOption(
  data: ChartData,
  ink: InkColors,
  valueFormatter: (v: number) => string,
): EChartsOption {
  const showLegend = data.series.length >= 2;

  const series: BarSeriesOption[] = data.series.map((s) => ({
    type: 'bar',
    name: s.name,
    data: s.data,
    color: s.color,
    itemStyle: { color: s.color, borderRadius: [4, 4, 0, 0] },
    barGap: '2px',
    barCategoryGap: '20%',
  }));

  return {
    backgroundColor: 'transparent',
    textStyle: { color: ink.text },
    grid: { left: 44, right: 24, top: 20, bottom: 32 },
    legend: showLegend ? { show: true, top: 0, textStyle: { color: ink.text } } : { show: false },
    tooltip: {
      trigger: 'item',
      valueFormatter: (value) => (typeof value === 'number' ? valueFormatter(value) : '—'),
    },
    xAxis: {
      type: 'category',
      data: data.weeks,
      axisLine: { lineStyle: { color: ink.muted } },
      axisLabel: { color: ink.muted },
      splitLine: { show: false },
    },
    yAxis: {
      type: 'value',
      axisLine: { show: false },
      axisLabel: { color: ink.muted, formatter: (value: number) => valueFormatter(value) },
      splitLine: { lineStyle: { color: ink.grid } },
    },
    series,
  };
}
