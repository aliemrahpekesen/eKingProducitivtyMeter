import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TenantContext } from '../app/tenantContext';
import type { FrictionSummaryView, TeamFrictionView } from '../api/types';
import { useFrictionSummary } from '../api/hooks';
import { FrictionCard } from './FrictionCard';

vi.mock('../api/hooks', () => ({ useFrictionSummary: vi.fn() }));
const mockedUseFriction = vi.mocked(useFrictionSummary);

type FrictionResult = ReturnType<typeof useFrictionSummary>;

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
});
