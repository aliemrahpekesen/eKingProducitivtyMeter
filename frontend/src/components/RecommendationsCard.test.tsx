import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { TeamRecommendationsView } from '../api/types';
import { useRecommendations } from '../api/hooks';
import { TenantContext } from '../app/tenantContext';
import { RecommendationsCard } from './RecommendationsCard';

vi.mock('../api/hooks', () => ({
  useRecommendations: vi.fn(),
}));
const mockedUseRecommendations = vi.mocked(useRecommendations);

type RecommendationsResult = ReturnType<typeof useRecommendations>;

function renderCard(): void {
  render(
    <TenantContext.Provider value={{ tenantId: 'tenant-1', setTenantId: () => {} }}>
      <RecommendationsCard />
    </TenantContext.Provider>,
  );
}

const teams: TeamRecommendationsView[] = [
  {
    teamId: 'platform',
    teamName: 'Platform',
    frictionScore: 91,
    recommendations: [
      {
        code: 'REVIEW_WAIT_HIGH',
        severity: 'CRITICAL',
        title: 'Review wait dominates cycle time',
        rationale: 'Review wait is 57% of cycle time, well above the 25% healthy threshold.',
        actions: ['Add a second reviewer rotation', 'Cap WIP per reviewer'],
        metricRefs: ['reviewWaitPct=57', 'frictionScore=91'],
      },
    ],
  },
  {
    teamId: 'payments',
    teamName: 'Payments',
    frictionScore: 40,
    recommendations: [],
  },
];

describe('RecommendationsCard', () => {
  it('renders a team group with a friction chip, CRITICAL chip text, rationale numbers, and actions', () => {
    mockedUseRecommendations.mockReturnValue({
      data: teams,
      isLoading: false,
      isError: false,
    } as unknown as RecommendationsResult);

    renderCard();

    expect(screen.getByText('Platform')).toBeInTheDocument();
    expect(screen.getByText('91/100')).toBeInTheDocument();
    expect(screen.getByText(/CRITICAL/)).toBeInTheDocument();
    expect(screen.getByText('Review wait dominates cycle time')).toBeInTheDocument();
    expect(screen.getByText(/57% of cycle time/)).toBeInTheDocument();
    expect(screen.getByText('Add a second reviewer rotation')).toBeInTheDocument();
    expect(screen.getByText(/reviewWaitPct=57/)).toBeInTheDocument();
    // Payments has zero recommendations — it should not render an empty group.
    expect(screen.queryByText('Payments')).not.toBeInTheDocument();
    expect(screen.getByText(/Rule-based & deterministic/)).toBeInTheDocument();
  });

  it('shows an empty state when no team has any recommendations', () => {
    mockedUseRecommendations.mockReturnValue({
      data: [{ ...teams[0], recommendations: [] }, teams[1]],
      isLoading: false,
      isError: false,
    } as unknown as RecommendationsResult);

    renderCard();

    expect(screen.getByText('No recommendations — teams look healthy.')).toBeInTheDocument();
  });

  it('shows a loading state', () => {
    mockedUseRecommendations.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
    } as unknown as RecommendationsResult);

    renderCard();

    expect(screen.getByRole('status')).toHaveTextContent('Loading recommendations…');
  });

  it('shows an error state with retry', () => {
    mockedUseRecommendations.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error('boom'),
      refetch: vi.fn(),
    } as unknown as RecommendationsResult);

    renderCard();

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
