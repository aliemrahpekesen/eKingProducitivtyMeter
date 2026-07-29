// Pure mapping functions from the metrics/trends API shape to chart-ready series. No echarts import
// here (echarts lives in useECharts.ts + the chart components) so this module stays trivially
// unit-testable and framework-agnostic.
import type { TeamTrendView, TrendPointView } from '../api/types';

/** Categorical team palettes, fixed order by teamName ascending (design-system validator rule). */
export const LIGHT_PALETTE: readonly string[] = ['#2a78d6', '#008300', '#e87ba4', '#eda100'];
export const DARK_PALETTE: readonly string[] = ['#3987e5', '#008300', '#d55181', '#c98500'];

/** Fixed color for the aggregate "Other" series (teams folded beyond the top 4). */
export const OTHER_COLOR = '#8a8a86';

export interface TeamIdentity {
  teamId: string;
  teamName: string;
}

/**
 * Assigns palette colors to teams in a FIXED order by teamName ascending — never re-assigned when
 * filtering, so the same team keeps the same color across every chart. Teams beyond the palette's
 * length (should not normally happen — callers pass the already-folded ≤4 "kept" teams) fall back to
 * the shared OTHER_COLOR rather than reusing a palette slot.
 */
export function assignTeamColors(
  teams: readonly TeamIdentity[],
  palette: readonly string[],
): Map<string, string> {
  const sorted = [...teams].sort((a, b) => a.teamName.localeCompare(b.teamName));
  const colors = new Map<string, string>();
  sorted.forEach((team, index) => {
    colors.set(team.teamId, index < palette.length ? palette[index] : OTHER_COLOR);
  });
  return colors;
}

export interface FoldResult {
  kept: TeamTrendView[];
  other: TeamTrendView | null;
}

/**
 * Keeps the top `max` teams by latest (most recent week's) frictionScore as individual series and
 * folds the rest into one "Other" aggregate: itemsResolved summed per week, every other numeric
 * field weighted-averaged by itemsResolved. `kept` is returned sorted by teamName ascending so it
 * lines up with assignTeamColors' fixed ordering.
 */
export function foldTeams(trends: readonly TeamTrendView[], max = 4): FoldResult {
  if (trends.length <= max) {
    return {
      kept: [...trends].sort((a, b) => a.teamName.localeCompare(b.teamName)),
      other: null,
    };
  }

  const latestScore = (team: TeamTrendView): number =>
    team.points.length > 0 ? team.points[team.points.length - 1].frictionScore : 0;

  const ranked = [...trends].sort((a, b) => {
    const diff = latestScore(b) - latestScore(a);
    return diff !== 0 ? diff : a.teamName.localeCompare(b.teamName);
  });

  const keptRaw = ranked.slice(0, max);
  const toFold = ranked.slice(max);

  const kept = [...keptRaw].sort((a, b) => a.teamName.localeCompare(b.teamName));
  const other = foldIntoOther(toFold);

  return { kept, other };
}

function foldIntoOther(teams: readonly TeamTrendView[]): TeamTrendView | null {
  const weekStarts = new Set<string>();
  for (const team of teams) {
    for (const point of team.points) {
      weekStarts.add(point.weekStart);
    }
  }
  if (weekStarts.size === 0) {
    return null;
  }

  const pointsByWeek = teams.map((team) => new Map(team.points.map((p) => [p.weekStart, p])));

  const points: TrendPointView[] = [...weekStarts].sort().map((weekStart) => {
    const contributing: TrendPointView[] = [];
    for (const byWeek of pointsByWeek) {
      const point = byWeek.get(weekStart);
      if (point !== undefined) {
        contributing.push(point);
      }
    }
    return foldPoint(weekStart, contributing);
  });

  return { teamId: 'other', teamName: 'Other', points };
}

/** Weighted average by itemsResolved; falls back to a plain average when total items is 0. */
function weightedAverage(
  points: readonly TrendPointView[],
  select: (p: TrendPointView) => number,
): number {
  const totalItems = points.reduce((sum, p) => sum + p.itemsResolved, 0);
  if (totalItems > 0) {
    const weighted = points.reduce((sum, p) => sum + select(p) * p.itemsResolved, 0);
    return weighted / totalItems;
  }
  if (points.length === 0) {
    return 0;
  }
  return points.reduce((sum, p) => sum + select(p), 0) / points.length;
}

function foldPoint(weekStart: string, contributing: readonly TrendPointView[]): TrendPointView {
  return {
    weekStart,
    itemsResolved: contributing.reduce((sum, p) => sum + p.itemsResolved, 0),
    avgCycleSec: weightedAverage(contributing, (p) => p.avgCycleSec),
    p85CycleSec: weightedAverage(contributing, (p) => p.p85CycleSec),
    flowEfficiencyPct: weightedAverage(contributing, (p) => p.flowEfficiencyPct),
    blockedPct: weightedAverage(contributing, (p) => p.blockedPct),
    reviewWaitPct: weightedAverage(contributing, (p) => p.reviewWaitPct),
    frictionScore: weightedAverage(contributing, (p) => p.frictionScore),
  };
}

export interface ChartSeries {
  name: string;
  color: string;
  data: (number | null)[];
}

export interface ChartData {
  weeks: string[];
  series: ChartSeries[];
}

/**
 * Aligns every team's points onto the union of weekStarts (ascending). A team missing a given week
 * gets `null` at that index (echarts connectNulls:false leaves a visible gap rather than lying with
 * an interpolated line).
 */
function alignSeries(
  teams: readonly TeamTrendView[],
  colors: ReadonlyMap<string, string>,
  select: (p: TrendPointView) => number,
): ChartData {
  const weeks = [...new Set(teams.flatMap((t) => t.points.map((p) => p.weekStart)))].sort();

  const series = teams.map((team) => {
    const byWeek = new Map(team.points.map((p) => [p.weekStart, p]));
    const data = weeks.map((week) => {
      const point = byWeek.get(week);
      return point === undefined ? null : select(point);
    });
    return {
      name: team.teamName,
      color: colors.get(team.teamId) ?? OTHER_COLOR,
      data,
    };
  });

  return { weeks, series };
}

export function toFrictionSeries(
  teams: readonly TeamTrendView[],
  colors: ReadonlyMap<string, string>,
): ChartData {
  return alignSeries(teams, colors, (p) => p.frictionScore);
}

export function toThroughputSeries(
  teams: readonly TeamTrendView[],
  colors: ReadonlyMap<string, string>,
): ChartData {
  return alignSeries(teams, colors, (p) => p.itemsResolved);
}

/** Average cycle time only (seconds) — deliberately avg-only across multiple teams; see toCycleP85Series. */
export function toCycleSeries(
  teams: readonly TeamTrendView[],
  colors: ReadonlyMap<string, string>,
): ChartData {
  return alignSeries(teams, colors, (p) => p.avgCycleSec);
}

/** p85 cycle time (seconds) — only overlaid in the UI when ≤2 teams are shown, to keep it honest. */
export function toCycleP85Series(
  teams: readonly TeamTrendView[],
  colors: ReadonlyMap<string, string>,
): ChartData {
  return alignSeries(teams, colors, (p) => p.p85CycleSec);
}

export function toFlowEffSeries(
  teams: readonly TeamTrendView[],
  colors: ReadonlyMap<string, string>,
): ChartData {
  return alignSeries(teams, colors, (p) => p.flowEfficiencyPct);
}
