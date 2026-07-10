// Types mirror backend/eip-app/openapi/eip-openapi-v1.json exactly (the source of truth). No field
// is added that the contract does not define. Nullable fields (organizationName, nextCursor, metric)
// reflect the backend's @Nullable / @JsonInclude(NON_NULL) serialization.

export interface SessionView {
  tenantId: string;
  organizationName: string | null;
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

export interface FrictionSummaryView {
  metric: FrictionMetricView | null;
  metricVersion: string | null;
  computedAt: string | null;
  simulation: boolean;
  teams: TeamFrictionView[];
  teamsReporting: number;
}
