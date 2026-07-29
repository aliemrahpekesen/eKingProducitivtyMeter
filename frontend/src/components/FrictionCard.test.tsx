import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TenantContext } from '../app/tenantContext';
import type { FrictionEvidenceView, FrictionSummaryView, TeamFrictionView } from '../api/types';
import { useFrictionEvidence, useFrictionSummary } from '../api/hooks';
import { FrictionCard } from './FrictionCard';

vi.mock('../api/hooks', () => ({
  useFrictionSummary: vi.fn(),
  useFrictionEvidence: vi.fn(),
}));
const mockedUseFriction = vi.mocked(useFrictionSummary);
const mockedUseEvidence = vi.mocked(useFrictionEvidence);
mockedUseEvidence.mockReturnValue({
  data: undefined,
  isLoading: false,
  isError: false,
} as unknown as ReturnType<typeof useFrictionEvidence>);

type FrictionResult = ReturnType<typeof useFrictionSummary>;
type EvidenceResult = ReturnType<typeof useFrictionEvidence>;

function renderCard(): void {
  render(
    <TenantContext.Provider value={{ tenantId: 'tenant-1', setTenantId: () => {} }}>
      <FrictionCard />
    </TenantContext.Provider>,
  );
}

function team(name: string, score: number, cause: string): TeamFrictionView {
  return {
    teamId: name,
    teamName: name,
    frictionScore: score,
    dominantCause: cause,
    workItems: 3,
    totalCycleSec: 356_400,
    activeSec: 50_400,
    waitingSec: 288_000,
    blockedSec: 86_400,
    reviewWaitSec: 201_600,
    reworkCount: 1,
    flowEfficiencyPct: 14,
    blockedPct: 24,
    reviewWaitPct: 57,
  };
}

const summary: FrictionSummaryView = {
  teamsReporting: 3,
  metricVersion: 'engineering_friction_v0.1',
  computedAt: '2026-01-07T21:00:00Z',
  simulation: true,
  metric: {
    key: 'engineering_friction',
    name: 'Engineering Friction (v0.1, experimental)',
    purpose: 'Where a team’s delivery time is lost.',
    formula: 'friction = round(min(100, 100*waitingRatio + 30*reworkPerItem))',
    inputs: { signals: ['work_item_transitions'] },
    grain: 'team',
    caveats: 'EXPERIMENTAL v0.1; team-level only.',
    gamingRisks: 'Skipping reviews understates it.',
  },
  teams: [
    team('Platform', 91, 'REVIEW_WAIT'),
    team('Payments', 56, 'REVIEW_WAIT'),
    team('Web', 50, 'BLOCKED'),
  ],
};

describe('FrictionCard', () => {
  it('renders the worst-first computed headline, component breakdown, and honest caveats', () => {
    mockedUseFriction.mockReturnValue({
      data: summary,
      isLoading: false,
      isError: false,
    } as unknown as FrictionResult);

    renderCard();

    expect(screen.getByLabelText('Top friction score')).toHaveTextContent('91');
    // Platform is the worst team → shown as the hero bottleneck AND first in the breakdown list.
    expect(screen.getAllByText('Platform')).toHaveLength(2);
    expect(screen.getByText('Payments')).toBeInTheDocument();
    expect(screen.getByText('Web')).toBeInTheDocument();
    expect(screen.getByText(/3 teams reporting/)).toBeInTheDocument();
    // Component breakdown + dominant cause are shown per team (computed, not seed).
    expect(screen.getAllByText(/active 14% · blocked 24% · review wait 57%/)).toHaveLength(3);
    expect(screen.getByText(/engineering_friction_v0.1/)).toBeInTheDocument();
    // Each team offers a drill-to-evidence drawer.
    expect(screen.getAllByText(/Show evidence for/)).toHaveLength(3);
    // FEAT-031: the metric's caveats + gaming risks are surfaced so the score is read honestly.
    expect(screen.getByText('EXPERIMENTAL v0.1; team-level only.')).toBeInTheDocument();
    expect(screen.getByText('Skipping reviews understates it.')).toBeInTheDocument();
  });

  it('shows an empty state when no teams report', () => {
    mockedUseFriction.mockReturnValue({
      data: { ...summary, teams: [], teamsReporting: 0 },
      isLoading: false,
      isError: false,
    } as unknown as FrictionResult);

    renderCard();

    expect(screen.getByText('No team friction data yet')).toBeInTheDocument();
  });

  it('shows a loading state while computing', () => {
    mockedUseFriction.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
    } as unknown as FrictionResult);

    renderCard();

    expect(screen.getByRole('status')).toHaveTextContent('Computing friction…');
  });

  it('shows an error state with a retry when the query fails', () => {
    mockedUseFriction.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error('boom'),
      refetch: vi.fn(),
    } as unknown as FrictionResult);

    renderCard();

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  // DEBT-020 item 5 regression: FrictionSummaryView is @JsonInclude(NON_NULL) on the backend, so
  // metric/metricVersion/computedAt are OMITTED from the JSON (never sent as `null`) when nothing
  // has been computed yet — the fixture below omits the keys entirely, exactly like the real
  // parsed response would. Before the fix, `!== null` was always true for an omitted/`undefined`
  // field, so this state rendered a bare "Metric" label and crashed reading `metric.name` off
  // `undefined` in <MetricExplainer>.
  it('hides the metric line and explainer when the backend omits metric/metricVersion/computedAt', () => {
    const summaryWithNothingComputedYet: FrictionSummaryView = {
      teamsReporting: 1,
      simulation: true,
      teams: [team('Platform', 91, 'REVIEW_WAIT')],
    };
    mockedUseFriction.mockReturnValue({
      data: summaryWithNothingComputedYet,
      isLoading: false,
      isError: false,
    } as unknown as FrictionResult);

    renderCard();

    expect(screen.queryByText(/^Metric /)).toBeNull();
    expect(screen.queryByText(/computed/)).toBeNull();
    expect(screen.queryByText(/is measured/)).toBeNull();
    expect(screen.getByText(/simulation data/)).toBeInTheDocument();
  });

  // DEBT-020 item 5 regression: WorkItemEvidenceView is likewise @JsonInclude(NON_NULL) —
  // pullRequestKey/buildKey/buildStatus/qualityGateKey/qualityGateStatus/workItemKey are OMITTED
  // (never `null`) when nothing correlated. Before the fix, `buildKey !== null` /
  // `qualityGateKey !== null` were always true for the omitted/`undefined` fields, rendering
  // literal "undefined (—)" artifact text.
  it('renders evidence honestly when the backend omits pullRequestKey/buildKey/qualityGateKey', () => {
    const evidenceWithNoCorrelatedArtifacts: FrictionEvidenceView = {
      teamId: 'platform',
      metricVersion: 'engineering_friction_v0.1',
      items: [
        {
          title: 'Fix flaky checkout test',
          type: 'TASK',
          status: 'DONE',
          cycleTimeSec: 86_400,
          activeSec: 43_200,
          blockedSec: 0,
          reviewWaitSec: 0,
          waitingSec: 0,
          reworkCount: 0,
          transitions: [{ seq: 1, toState: 'DONE', atEpochSec: 1_700_000_000 }],
        },
      ],
    };
    mockedUseFriction.mockReturnValue({
      data: summary,
      isLoading: false,
      isError: false,
    } as unknown as FrictionResult);
    mockedUseEvidence.mockReturnValue({
      data: evidenceWithNoCorrelatedArtifacts,
      isLoading: false,
      isError: false,
    } as unknown as EvidenceResult);

    renderCard();
    // jsdom does not simulate the browser's native click-toggles-<details> default action, so the
    // drawer is opened directly: flip `open` (as the browser would, before dispatching) and fire
    // the `toggle` event the component's onToggle handler reads `currentTarget.open` from.
    const details = screen.getAllByText(/Show evidence for/)[0].closest('details');
    expect(details).not.toBeNull();
    (details as HTMLDetailsElement).open = true;
    fireEvent(details as HTMLDetailsElement, new Event('toggle'));

    expect(screen.getByText('Fix flaky checkout test')).toBeInTheDocument();
    expect(screen.queryByText(/undefined/)).toBeNull();
    expect(screen.getByText('—')).toBeInTheDocument();
  });
});
