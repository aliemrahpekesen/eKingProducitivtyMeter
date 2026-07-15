import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReportDocumentView } from '../api/types';
import { useReport } from '../api/hooks';
import { TenantContext } from '../app/tenantContext';
import { ReportDetail } from './ReportDetail';

vi.mock('../api/hooks', () => ({
  useReport: vi.fn(),
}));

// This suite only smoke-tests the React shell (totals, team sections, recommendations, export
// controls) — chart internals have no DOM surface to assert on, same convention as
// TrendsBoard.test.tsx.
vi.mock('echarts', () => ({
  init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })),
}));

const mockedUseReport = vi.mocked(useReport);

type ReportResult = ReturnType<typeof useReport>;

class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

beforeAll(() => {
  vi.stubGlobal('ResizeObserver', ResizeObserverStub);
  vi.stubGlobal(
    'matchMedia',
    vi.fn().mockImplementation((query: string) => ({
      matches: false,
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  );
});

function renderDetail(onBack: () => void = vi.fn()): void {
  render(
    <TenantContext.Provider value={{ tenantId: 'tenant-1', setTenantId: () => {} }}>
      <ReportDetail reportId="r-1" onBack={onBack} />
    </TenantContext.Provider>,
  );
}

const documentView: ReportDocumentView = {
  report: {
    id: 'r-1',
    type: 'EXEC_SUMMARY',
    title: 'Exec summary — Jul 2026',
    status: 'COMPLETED',
    periodStart: '2026-04-06T00:00:00Z',
    periodEnd: '2026-07-05T00:00:00Z',
    weeks: 12,
    createdAt: '2026-07-15T09:00:00Z',
    completedAt: '2026-07-15T09:00:05Z',
  },
  document: {
    reportVersion: 1,
    title: 'Exec summary — Jul 2026',
    periodStart: '2026-04-06T00:00:00Z',
    periodEnd: '2026-07-05T00:00:00Z',
    weeks: 12,
    generatedAt: '2026-07-15T09:00:05Z',
    metricVersion: 'friction-v1',
    totals: {
      teamsReporting: 2,
      itemsResolved: 84,
      avgCycleSec: 100_000,
      p85CycleSec: 200_000,
      flowEfficiencyPct: 42,
      blockedPct: 18,
      reviewWaitPct: 25,
      avgFrictionScore: 61,
    },
    teams: [
      {
        teamId: 'platform',
        teamName: 'Platform',
        frictionScore: 91,
        dominantCause: 'REVIEW_WAIT',
        // Deliberately different from totals.avgCycleSec/p85CycleSec so the two hour-formatted
        // strings ("27.8h"/"55.6h" vs "20.0h"/"40.0h") don't collide in a getByText query.
        points: [
          {
            weekStart: '2026-07-06',
            itemsResolved: 10,
            avgCycleSec: 72_000,
            p85CycleSec: 144_000,
            flowEfficiencyPct: 40,
            blockedPct: 20,
            reviewWaitPct: 30,
            frictionScore: 70,
          },
        ],
        recommendations: [
          {
            code: 'REVIEW_WAIT_HIGH',
            severity: 'CRITICAL',
            title: 'Review wait dominates cycle time',
            rationale: 'Review wait is 57% of cycle time, well above the 25% healthy threshold.',
            actions: ['Add a second reviewer rotation'],
            metricRefs: ['reviewWaitPct=57'],
          },
        ],
        inFlightCount: 12,
        blockedInFlightCount: 3,
      },
    ],
  },
};

describe('ReportDetail', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    vi.stubGlobal('ResizeObserver', ResizeObserverStub);
    vi.stubGlobal(
      'matchMedia',
      vi.fn().mockImplementation((query: string) => ({
        matches: false,
        media: query,
        addEventListener: vi.fn(),
        removeEventListener: vi.fn(),
      })),
    );
  });

  it('shows a loading state', () => {
    mockedUseReport.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
    } as unknown as ReportResult);

    renderDetail();

    expect(screen.getByRole('status')).toHaveTextContent('Loading report…');
  });

  it('shows an error state with retry', () => {
    mockedUseReport.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error('boom'),
      refetch: vi.fn(),
    } as unknown as ReportResult);

    renderDetail();

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('renders the deterministic note, header metadata, and totals', () => {
    mockedUseReport.mockReturnValue({
      data: documentView,
      isLoading: false,
      isError: false,
    } as unknown as ReportResult);

    renderDetail();

    expect(screen.getByText('Exec summary — Jul 2026')).toBeInTheDocument();
    expect(
      screen.getByText('Deterministic report — computed from recorded flow data; no AI involved.'),
    ).toBeInTheDocument();
    // Period is a date range, not a timestamp — rendered as the date part only, even though the
    // API sends full ISO instants ("2026-04-06T00:00:00Z").
    expect(screen.getByText(/2026-04-06 → 2026-07-05 · 12 weeks/)).toBeInTheDocument();
    expect(screen.getByText(/generated 2026-07-15 09:00 UTC/)).toBeInTheDocument();
    expect(screen.getByText(/metric friction-v1/)).toBeInTheDocument();
    expect(screen.getByText('84')).toBeInTheDocument(); // items resolved
    expect(screen.getByText('27.8h')).toBeInTheDocument(); // avg cycle: 100000s
    expect(screen.getByText('55.6h')).toBeInTheDocument(); // p85 cycle: 200000s
    expect(screen.getByText('61/100')).toBeInTheDocument(); // avg friction score
  });

  it('renders a per-team section with friction score, dominant cause, and severity text+icon', () => {
    mockedUseReport.mockReturnValue({
      data: documentView,
      isLoading: false,
      isError: false,
    } as unknown as ReportResult);

    renderDetail();

    expect(screen.getByText('Platform')).toBeInTheDocument();
    expect(screen.getByText('91/100')).toBeInTheDocument();
    expect(screen.getByText('review wait')).toBeInTheDocument();
    expect(screen.getByText(/CRITICAL/)).toBeInTheDocument();
    expect(screen.getByText('Review wait dominates cycle time')).toBeInTheDocument();
    expect(screen.getByText(/57% of cycle time/)).toBeInTheDocument();
    expect(screen.getByText(/12 in flight/)).toBeInTheDocument();
    expect(screen.getByText(/3 blocked/)).toBeInTheDocument();
  });

  it('calls onBack when the back button is clicked', () => {
    mockedUseReport.mockReturnValue({
      data: documentView,
      isLoading: false,
      isError: false,
    } as unknown as ReportResult);
    const onBack = vi.fn();

    renderDetail(onBack);
    fireEvent.click(screen.getByRole('button', { name: '← Back to reports' }));

    expect(onBack).toHaveBeenCalled();
  });

  it('calls window.print when the Print button is clicked', () => {
    mockedUseReport.mockReturnValue({
      data: documentView,
      isLoading: false,
      isError: false,
    } as unknown as ReportResult);
    const printSpy = vi.fn();
    vi.stubGlobal('print', printSpy);

    renderDetail();
    fireEvent.click(screen.getByRole('button', { name: 'Print' }));

    expect(printSpy).toHaveBeenCalled();
  });

  it('fetches the HTML export with the tenant header and opens it as a Blob URL', async () => {
    mockedUseReport.mockReturnValue({
      data: documentView,
      isLoading: false,
      isError: false,
    } as unknown as ReportResult);

    const html = '<html><body>Exec summary</body></html>';
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      text: () => Promise.resolve(html),
    });
    vi.stubGlobal('fetch', fetchMock);
    const createObjectURL = vi.fn().mockReturnValue('blob:mock-url');
    vi.stubGlobal('URL', { ...URL, createObjectURL });
    const openSpy = vi.fn();
    vi.stubGlobal('open', openSpy);

    renderDetail();
    fireEvent.click(screen.getByRole('button', { name: 'Open HTML' }));

    await waitFor(() => expect(openSpy).toHaveBeenCalledWith('blob:mock-url', '_blank'));

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/reports/r-1/html',
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-EIP-Tenant': 'tenant-1' }),
      }),
    );
    expect(createObjectURL).toHaveBeenCalled();
    const blobArg = createObjectURL.mock.calls[0][0] as Blob;
    expect(blobArg.type).toBe('text/html');
  });

  it('shows an error state when the HTML export fetch fails', async () => {
    mockedUseReport.mockReturnValue({
      data: documentView,
      isLoading: false,
      isError: false,
    } as unknown as ReportResult);

    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      json: () => Promise.resolve({ title: 'Render failed' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    renderDetail();
    fireEvent.click(screen.getByRole('button', { name: 'Open HTML' }));

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByText('Render failed')).toBeInTheDocument();
  });
});
