// Types mirror backend/eip-app/openapi/eip-openapi-v1.json exactly (the source of truth). No field
// is added that the contract does not define.
//
// Two distinct backend nullability idioms map to two different TS shapes here — conflating them is
// a real bug (DEBT-020 item 5): checking `=== null`/`!== null` against a field the backend actually
// OMITS never fires (the parsed value is `undefined`, never `null`).
//   - `@Nullable` field, NO `@JsonInclude(NON_NULL)` on the class (e.g. SessionView.organizationName,
//     AuthConfigView.issuer/clientId, AiPolicyView.provider/baseUrl/model,
//     ConnectorTypeView.secretLabel): Jackson's default ALWAYS inclusion serializes a real JSON
//     `null` — typed here as `T | null`, and `=== null`/`!== null` checks against them are correct.
//   - `@Nullable` field on a class/record annotated `@JsonInclude(Include.NON_NULL)` (PageView's
//     nextCursor, FrictionSummaryView's metric/metricVersion/computedAt, FrictionEvidenceView's
//     teamName, its nested WorkItemEvidenceView's workItemKey/pullRequestKey/buildKey/buildStatus/
//     qualityGateKey/qualityGateStatus, and its nested TransitionEvidenceView's fromState): the field
//     is OMITTED entirely when null, so it arrives as `undefined` — typed here as an optional
//     property (`field?: T`), never `T | null`.

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
  /** Omitted (never `null`) when `hasMore` is false — {@code PageView}'s `@JsonInclude(NON_NULL)`. */
  nextCursor?: string;
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
  fromState?: string;
  toState: string;
  atEpochSec: number;
}

export interface WorkItemEvidenceView {
  workItemKey?: string;
  title: string;
  type: string;
  status: string;
  cycleTimeSec: number;
  activeSec: number;
  blockedSec: number;
  reviewWaitSec: number;
  waitingSec: number;
  reworkCount: number;
  pullRequestKey?: string;
  buildKey?: string;
  buildStatus?: string;
  qualityGateKey?: string;
  qualityGateStatus?: string;
  transitions: TransitionEvidenceView[];
}

export interface FrictionEvidenceView {
  teamId: string;
  teamName?: string;
  metricVersion: string;
  items: WorkItemEvidenceView[];
}

export interface FrictionSummaryView {
  metric?: FrictionMetricView;
  metricVersion?: string;
  computedAt?: string;
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
  /** Omitted (never `null`) when `hasMore` is false — {@code ReportPageView}'s `@JsonInclude(NON_NULL)`. */
  nextCursor?: string;
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

// ── M6-B optional AI explanation layer (frontend) ──────────────────────────────────────────────
// AI is optional, per-tenant, OFF by default. It only ever EXPLAINS deterministic numbers already
// computed elsewhere (trends, recommendations, reports) — it is never a new metric source and
// never surfaces individual-level detail (NFR-071). Every AI output carries the API's own
// `disclaimer` string, rendered verbatim, never paraphrased.

export interface AiPolicyView {
  enabled: boolean;
  provider: 'ollama' | 'openai-compatible' | null;
  baseUrl: string | null;
  model: string | null;
  /** Whether a secret is stored server-side. The secret itself is write-only — never returned. */
  hasSecret: boolean;
  temperature: number;
  maxTokens: number;
}

/** GET /api/v1/ai/status — any dashboard viewer (not admin-only), unlike AiPolicyView. */
export interface AiStatusView {
  enabled: boolean;
}

export interface ExplanationView {
  narrative: string;
  provider: string;
  model: string;
  /** Numbers the narrative cited, verified against the deterministic source before being shown. */
  citedNumbers: string[];
  generatedAt: string;
  disclaimer: string;
}
