import { fireEvent, render, screen } from '@testing-library/react';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import type { ReportView } from '../api/types';
import { useGenerateReport, useReport, useReports } from '../api/hooks';
import { TenantContext } from '../app/tenantContext';
import { ReportsPanel } from './ReportsPanel';

vi.mock('../api/hooks', () => ({
  useReports: vi.fn(),
  useGenerateReport: vi.fn(),
  useReport: vi.fn(),
}));

// ReportsPanel statically imports ReportDetail (rendered once a report is selected), which pulls in
// vanilla echarts through useECharts — mocked here exactly as TrendsBoard.test.tsx does, since this
// suite never selects a report and only smoke-tests the library/generate shell.
vi.mock('echarts', () => ({
  init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() })),
}));

const mockedUseReports = vi.mocked(useReports);
const mockedUseGenerateReport = vi.mocked(useGenerateReport);
const mockedUseReport = vi.mocked(useReport);

type ReportsResult = ReturnType<typeof useReports>;
type GenerateResult = ReturnType<typeof useGenerateReport>;

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

function renderPanel(): void {
  render(
    <TenantContext.Provider value={{ tenantId: 'tenant-1', setTenantId: () => {} }}>
      <ReportsPanel />
    </TenantContext.Provider>,
  );
}

const idleGenerate = {
  mutate: vi.fn(),
  isPending: false,
  isError: false,
} as unknown as GenerateResult;

const report: ReportView = {
  id: 'r-1',
  type: 'EXEC_SUMMARY',
  title: 'Exec summary — Jul 2026',
  status: 'COMPLETED',
  periodStart: '2026-04-06T00:00:00Z',
  periodEnd: '2026-07-05T00:00:00Z',
  weeks: 12,
  createdAt: '2026-07-15T09:00:00Z',
  completedAt: '2026-07-15T09:00:05Z',
};

function armEmpty(): void {
  mockedUseReports.mockReturnValue({
    data: { pages: [{ items: [], hasMore: false, nextCursor: null }], pageParams: [undefined] },
    isLoading: false,
    isError: false,
    hasNextPage: false,
    isFetchingNextPage: false,
  } as unknown as ReportsResult);
  mockedUseGenerateReport.mockReturnValue(idleGenerate);
}

describe('ReportsPanel', () => {
  it('shows an honest empty state when there are no reports yet', () => {
    armEmpty();
    renderPanel();

    expect(screen.getByText('No reports yet — generate your first report')).toBeInTheDocument();
  });

  it('renders the report library: title, period, weeks, status text+icon, and created time', () => {
    mockedUseReports.mockReturnValue({
      data: {
        pages: [{ items: [report], hasMore: false, nextCursor: null }],
        pageParams: [undefined],
      },
      isLoading: false,
      isError: false,
      hasNextPage: false,
      isFetchingNextPage: false,
    } as unknown as ReportsResult);
    mockedUseGenerateReport.mockReturnValue(idleGenerate);

    renderPanel();

    expect(screen.getByText('Exec summary — Jul 2026')).toBeInTheDocument();
    expect(screen.getByText('2026-04-06 → 2026-07-05 · 12w')).toBeInTheDocument();
    expect(screen.getByText(/✓ COMPLETED/)).toBeInTheDocument();
    expect(screen.getByText(/2026-07-15 09:00 UTC/)).toBeInTheDocument();
  });

  it('defaults the period to 12 weeks and generates a report with the selected period', () => {
    armEmpty();
    renderPanel();

    expect(screen.getByRole('combobox', { name: /Report period/i })).toHaveValue('12');

    fireEvent.click(screen.getByRole('button', { name: 'Generate report' }));

    expect(idleGenerate.mutate).toHaveBeenCalledWith({ weeks: 12 });
  });

  it('generates with the newly selected period after changing it', () => {
    armEmpty();
    renderPanel();

    fireEvent.change(screen.getByRole('combobox', { name: /Report period/i }), {
      target: { value: '26' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Generate report' }));

    expect(idleGenerate.mutate).toHaveBeenLastCalledWith({ weeks: 26 });
  });

  it('disables the generate button while the mutation is pending', () => {
    mockedUseReports.mockReturnValue({
      data: { pages: [{ items: [], hasMore: false, nextCursor: null }], pageParams: [undefined] },
      isLoading: false,
      isError: false,
      hasNextPage: false,
      isFetchingNextPage: false,
    } as unknown as ReportsResult);
    mockedUseGenerateReport.mockReturnValue({
      mutate: vi.fn(),
      isPending: true,
      isError: false,
    } as unknown as GenerateResult);

    renderPanel();

    expect(screen.getByRole('button', { name: 'Generating…' })).toBeDisabled();
  });

  it('shows a loading state', () => {
    mockedUseReports.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      hasNextPage: false,
      isFetchingNextPage: false,
    } as unknown as ReportsResult);
    mockedUseGenerateReport.mockReturnValue(idleGenerate);

    renderPanel();

    expect(screen.getByRole('status')).toHaveTextContent('Loading reports…');
  });

  it('shows an error state with retry', () => {
    mockedUseReports.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error('boom'),
      refetch: vi.fn(),
      hasNextPage: false,
      isFetchingNextPage: false,
    } as unknown as ReportsResult);
    mockedUseGenerateReport.mockReturnValue(idleGenerate);

    renderPanel();

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('shows a Load more button and fetches the next page on click', () => {
    const fetchNextPage = vi.fn();
    mockedUseReports.mockReturnValue({
      data: {
        pages: [{ items: [report], hasMore: true, nextCursor: 'cursor-2' }],
        pageParams: [undefined],
      },
      isLoading: false,
      isError: false,
      hasNextPage: true,
      isFetchingNextPage: false,
      fetchNextPage,
    } as unknown as ReportsResult);
    mockedUseGenerateReport.mockReturnValue(idleGenerate);

    renderPanel();
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }));

    expect(fetchNextPage).toHaveBeenCalled();
  });

  it('opens the report detail when a row is clicked', () => {
    mockedUseReports.mockReturnValue({
      data: {
        pages: [{ items: [report], hasMore: false, nextCursor: null }],
        pageParams: [undefined],
      },
      isLoading: false,
      isError: false,
      hasNextPage: false,
      isFetchingNextPage: false,
    } as unknown as ReportsResult);
    mockedUseGenerateReport.mockReturnValue(idleGenerate);
    mockedUseReport.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
    } as unknown as ReturnType<typeof useReport>);

    renderPanel();
    fireEvent.click(screen.getByRole('button', { name: /Exec summary — Jul 2026/ }));

    // The list is replaced by the detail shell (back button + report-detail landmark).
    expect(screen.getByRole('button', { name: '← Back to reports' })).toBeInTheDocument();
    expect(screen.queryByText('No reports yet — generate your first report')).toBeNull();
  });
});
