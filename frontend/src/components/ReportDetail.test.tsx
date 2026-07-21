import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import type { ExplanationView, ReportDocumentView } from '../api/types';
import { useAiStatus, useReport, useReportNarrative } from '../api/hooks';
import { TenantContext } from '../app/tenantContext';
import { ReportDetail } from './ReportDetail';

vi.mock('../api/hooks', () => ({
  useReport: vi.fn(),
  useAiStatus: vi.fn(),
  useReportNarrative: vi.fn(),
}));

// This suite only smoke-tests the React shell (totals, team sections, recommendations, export
// controls) — chart internals have no DOM surface to assert on, same convention as
// TrendsBoard.test.tsx.
vi.mock('echarts', () => ({
  init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })),
}));

const mockedUseReport = vi.mocked(useReport);
const mockedUseAiStatus = vi.mocked(useAiStatus);
const mockedUseReportNarrative = vi.mocked(useReportNarrative);

type ReportResult = ReturnType<typeof useReport>;
type AiStatusResult = ReturnType<typeof useAiStatus>;
type NarrativeResult = ReturnType<typeof useReportNarrative>;

const aiDisabled = {
  data: { enabled: false },
  isLoading: false,
  isError: false,
} as unknown as AiStatusResult;
const aiEnabled = {
  data: { enabled: true },
  isLoading: false,
  isError: false,
} as unknown as AiStatusResult;
const idleNarrative = {
  mutate: vi.fn(),
  isPending: false,
  isError: false,
  isSuccess: false,
} as unknown as NarrativeResult;

const narrativeView: ExplanationView = {
  narrative: 'Platform carried the most review wait this period.',
  provider: 'ollama',
  model: 'llama3.1',
  citedNumbers: ['avgFrictionScore=61', 'reviewWaitPct=25'],
  generatedAt: '2026-07-15T09:00:05Z',
  disclaimer: 'AI-generated explanation — verify against the numbers above.',
};

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
    // Default: AI off for the tenant — the per-test AI narrative suite below overrides this.
    mockedUseAiStatus.mockReturnValue(aiDisabled);
    mockedUseReportNarrative.mockReturnValue(idleNarrative);
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

  describe('AI narrative (M6-B)', () => {
    it('hides the AI narrative button when AI is off for the tenant', () => {
      mockedUseReport.mockReturnValue({
        data: documentView,
        isLoading: false,
        isError: false,
      } as unknown as ReportResult);

      renderDetail();

      expect(screen.queryByRole('button', { name: 'AI narrative' })).not.toBeInTheDocument();
    });

    it('shows the AI narrative button and posts to the narrative endpoint when AI is on', () => {
      mockedUseReport.mockReturnValue({
        data: documentView,
        isLoading: false,
        isError: false,
      } as unknown as ReportResult);
      mockedUseAiStatus.mockReturnValue(aiEnabled);
      const mutate = vi.fn();
      mockedUseReportNarrative.mockReturnValue({
        mutate,
        isPending: false,
        isError: false,
        isSuccess: false,
      } as unknown as NarrativeResult);

      renderDetail();
      fireEvent.click(screen.getByRole('button', { name: 'AI narrative' }));

      expect(mutate).toHaveBeenCalledWith();
    });

    it('renders the narrative above the totals, no-print, labeled AI-generated with the disclaimer verbatim', () => {
      mockedUseReport.mockReturnValue({
        data: documentView,
        isLoading: false,
        isError: false,
      } as unknown as ReportResult);
      mockedUseAiStatus.mockReturnValue(aiEnabled);
      mockedUseReportNarrative.mockReturnValue({
        mutate: vi.fn(),
        data: narrativeView,
        isPending: false,
        isError: false,
        isSuccess: true,
      } as unknown as NarrativeResult);

      renderDetail();

      expect(screen.getByText('AI-generated')).toBeInTheDocument();
      expect(screen.getByText(narrativeView.narrative)).toBeInTheDocument();
      expect(screen.getByText(narrativeView.disclaimer)).toBeInTheDocument();
      expect(screen.getByText('Numbers verified: 2')).toBeInTheDocument();
      // Deterministic document stays the record — the narrative panel is marked no-print.
      expect(screen.getByText('AI-generated').closest('.no-print')).not.toBeNull();
    });

    it('shows a rejected-502 error honestly, with the problem detail and a working retry', () => {
      mockedUseReport.mockReturnValue({
        data: documentView,
        isLoading: false,
        isError: false,
      } as unknown as ReportResult);
      mockedUseAiStatus.mockReturnValue(aiEnabled);
      const mutate = vi.fn();
      mockedUseReportNarrative.mockReturnValue({
        mutate,
        isPending: false,
        isError: true,
        error: new ApiError(502, {
          title: 'AI narrative rejected',
          detail: 'narrative failed numeric verification — discarded',
        }),
        isSuccess: false,
      } as unknown as NarrativeResult);

      renderDetail();

      expect(
        screen.getByText('narrative failed numeric verification — discarded'),
      ).toBeInTheDocument();

      fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
      expect(mutate).toHaveBeenCalledWith();
    });
  });
});
