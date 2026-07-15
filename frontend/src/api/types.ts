// Types mirror backend/eip-app/openapi/eip-openapi-v1.json exactly (the source of truth). No field
// is added that the contract does not define. Nullable fields (organizationName, nextCursor, metric)
// reflect the backend's @Nullable / @JsonInclude(NON_NULL) serialization.

export interface SessionView {
  tenantId: string;
  organizationName: string | null;
}

// ── M5 OIDC login (Wave S1b) ────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/session/auth — no auth, no tenant header. Tells the SPA how to authenticate: HEADER
 * (today's dev tenant header, zero-auth) or OIDC (Authorization Code + PKCE against `issuer`).
 */
export interface AuthConfigView {
  mode: 'HEADER' | 'OIDC';
  issuer: string | null;
  clientId: string | null;
}

export interface ConnectorView {
  id: string;
  type: string;
  name: string;
  status: string;
  simulation: boolean;
}

export interface PageViewConnectorView {
  items: ConnectorView[];
  nextCursor?: string | null;
  hasMore: boolean;
}

export interface TeamFrictionView {
  teamId: string;
  teamName: string;
  frictionScore: number;
  dominantCause: string;
  workItems: number;
  totalCycleSec: number;
  activeSec: number;
  waitingSec: number;
  blockedSec: number;
  reviewWaitSec: number;
  reworkCount: number;
  flowEfficiencyPct: number;
  blockedPct: number;
  reviewWaitPct: number;
}

export interface FrictionMetricView {
  key: string;
  name: string;
  purpose: string;
  formula: string;
  /** JsonNode in the contract — an arbitrary JSON object of the metric's inputs. */
  inputs: unknown;
  grain: string;
  caveats: string;
  gamingRisks: string;
}

export interface TransitionEvidenceView {
  seq: number;
  fromState: string | null;
  toState: string;
  atEpochSec: number;
}

export interface WorkItemEvidenceView {
  workItemKey: string | null;
  title: string;
  type: string;
  status: string;
  cycleTimeSec: number;
  activeSec: number;
  blockedSec: number;
  reviewWaitSec: number;
  waitingSec: number;
  reworkCount: number;
  pullRequestKey: string | null;
  buildKey: string | null;
  buildStatus: string | null;
  qualityGateKey: string | null;
  qualityGateStatus: string | null;
  transitions: TransitionEvidenceView[];
}

export interface FrictionEvidenceView {
  teamId: string;
  teamName: string | null;
  metricVersion: string;
  items: WorkItemEvidenceView[];
}

export interface FrictionSummaryView {
  metric: FrictionMetricView | null;
  metricVersion: string | null;
  computedAt: string | null;
  simulation: boolean;
  teams: TeamFrictionView[];
  teamsReporting: number;
}

// ── M1 admin panel (ADR-022) ────────────────────────────────────────────────────────────────────

export interface TenantView {
  id: string;
  name: string;
  slug: string;
}

export interface TeamAdminView {
  id: string;
  name: string;
}

export interface BusinessUnitView {
  id: string;
  name: string;
  teams: TeamAdminView[];
}

export interface OrganizationView {
  id: string;
  name: string;
  slug: string;
  businessUnits: BusinessUnitView[];
}

export interface ConnectorTypeView {
  type: string;
  displayName: string;
  description: string;
  /** JSON Schema (draft 2020-12) text driving the config form. */
  configSchema: string;
  secretLabel: string | null;
  syncAvailable: boolean;
}

export interface ConnectorAdminView {
  id: string;
  type: string;
  name: string;
  status: string;
  simulation: boolean;
  config: Record<string, string>;
  hasSecret: boolean;
}

export interface TestConnectionResult {
  outcome: 'OK' | 'NOT_AVAILABLE' | 'FAILED';
  message: string;
}

export interface SampleDataResult {
  ingestion: { emitted: number; inserted: number; updated: number; unchanged: number };
  teamsComputed: number;
  itemsCorrelated: number;
}

// ── M3 dashboard: trends, in-flight, recommendations (TASK-0020) ──────────────────────────────────

export interface TrendPointView {
  /** ISO date, e.g. "2026-01-05". */
  weekStart: string;
  itemsResolved: number;
  avgCycleSec: number;
  p85CycleSec: number;
  flowEfficiencyPct: number;
  blockedPct: number;
  reviewWaitPct: number;
  frictionScore: number;
}

export interface TeamTrendView {
  teamId: string;
  teamName: string;
  /** Ascending by weekStart. */
  points: TrendPointView[];
}

export interface TrendsView {
  rangeWeeks: number;
  teams: TeamTrendView[];
}

export interface InFlightItemView {
  workItemKey: string;
  title: string;
  state: string;
  ageSec: number;
  blocked: boolean;
}

export interface TeamInFlightView {
  teamId: string;
  teamName: string;
  items: InFlightItemView[];
}

export interface RecommendationView {
  code: string;
  severity: 'INFO' | 'WARN' | 'CRITICAL';
  title: string;
  rationale: string;
  actions: string[];
  metricRefs: string[];
}

export interface TeamRecommendationsView {
  teamId: string;
  teamName: string;
  frictionScore: number;
  recommendations: RecommendationView[];
}

// ── M4 deterministic reports (TASK-0022) ───────────────────────────────────────────────────────

export interface ReportView {
  id: string;
  type: string;
  title: string;
  status: string;
  /** ISO date, e.g. "2026-01-05". */
  periodStart: string;
  periodEnd: string;
  weeks: number;
  createdAt: string;
  completedAt: string | null;
}

export interface PageViewReportView {
  items: ReportView[];
  nextCursor?: string | null;
  hasMore: boolean;
}

export interface ReportTotals {
  teamsReporting: number;
  itemsResolved: number;
  avgCycleSec: number;
  p85CycleSec: number;
  flowEfficiencyPct: number;
  blockedPct: number;
  reviewWaitPct: number;
  avgFrictionScore: number;
}

export interface ReportTeamSection {
  teamId: string;
  teamName: string;
  frictionScore: number;
  dominantCause: string;
  /** Ascending by weekStart. */
  points: TrendPointView[];
  recommendations: RecommendationView[];
  inFlightCount: number;
  blockedInFlightCount: number;
}

export interface ReportDocument {
  reportVersion: number;
  title: string;
  periodStart: string;
  periodEnd: string;
  weeks: number;
  generatedAt: string;
  metricVersion: string;
  totals: ReportTotals;
  teams: ReportTeamSection[];
}

export interface ReportDocumentView {
  report: ReportView;
  document: ReportDocument;
}
