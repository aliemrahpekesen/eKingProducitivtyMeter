import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/client';
import { useAiStatus, useExplainInsights } from '../api/hooks';
import type { ExplanationView } from '../api/types';
import { TenantContext } from '../app/tenantContext';
import { AiExplainCard } from './AiExplainCard';

vi.mock('../api/hooks', () => ({
  useAiStatus: vi.fn(),
  useExplainInsights: vi.fn(),
}));

const mockedUseAiStatus = vi.mocked(useAiStatus);
const mockedUseExplainInsights = vi.mocked(useExplainInsights);

type StatusResult = ReturnType<typeof useAiStatus>;
type ExplainResult = ReturnType<typeof useExplainInsights>;

function renderCard(): ReturnType<typeof render> {
  return render(
    <TenantContext.Provider value={{ tenantId: 'tenant-1', setTenantId: () => {} }}>
      <AiExplainCard />
    </TenantContext.Provider>,
  );
}

const explanation: ExplanationView = {
  narrative: "Platform's cycle time rose mainly due to review wait.",
  provider: 'ollama',
  model: 'llama3.1',
  citedNumbers: ['reviewWaitPct=57', 'frictionScore=91'],
  generatedAt: '2026-07-15T09:00:00Z',
  disclaimer: 'AI-generated explanation — verify against the numbers above.',
};

describe('AiExplainCard', () => {
  it('renders nothing when AI is off for the tenant', () => {
    mockedUseAiStatus.mockReturnValue({
      data: { enabled: false },
      isLoading: false,
      isError: false,
    } as unknown as StatusResult);
    mockedUseExplainInsights.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
      isError: false,
      isSuccess: false,
    } as unknown as ExplainResult);

    const { container } = renderCard();

    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing while AI status is still loading (fail-closed, not a flash of the button)', () => {
    mockedUseAiStatus.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
    } as unknown as StatusResult);
    mockedUseExplainInsights.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
      isError: false,
      isSuccess: false,
    } as unknown as ExplainResult);

    const { container } = renderCard();

    expect(container).toBeEmptyDOMElement();
  });

  it('renders the Explain with AI button when the tenant has AI enabled', () => {
    mockedUseAiStatus.mockReturnValue({
      data: { enabled: true },
      isLoading: false,
      isError: false,
    } as unknown as StatusResult);
    mockedUseExplainInsights.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
      isError: false,
      isSuccess: false,
    } as unknown as ExplainResult);

    renderCard();

    expect(screen.getByRole('button', { name: 'Explain with AI' })).toBeInTheDocument();
  });

  it('posts the fixed 12-week window when clicked (TrendsBoard range is local, not lifted)', () => {
    mockedUseAiStatus.mockReturnValue({
      data: { enabled: true },
      isLoading: false,
      isError: false,
    } as unknown as StatusResult);
    const mutate = vi.fn();
    mockedUseExplainInsights.mockReturnValue({
      mutate,
      isPending: false,
      isError: false,
      isSuccess: false,
    } as unknown as ExplainResult);

    renderCard();
    fireEvent.click(screen.getByRole('button', { name: 'Explain with AI' }));

    expect(mutate).toHaveBeenCalledWith({ weeks: 12 });
  });

  it('renders the narrative with the AI-generated badge, disclaimer, provider/model, and verified count', () => {
    mockedUseAiStatus.mockReturnValue({
      data: { enabled: true },
      isLoading: false,
      isError: false,
    } as unknown as StatusResult);
    mockedUseExplainInsights.mockReturnValue({
      mutate: vi.fn(),
      data: explanation,
      isPending: false,
      isError: false,
      isSuccess: true,
    } as unknown as ExplainResult);

    renderCard();

    expect(screen.getByText('AI-generated')).toBeInTheDocument();
    expect(screen.getByText(explanation.narrative)).toBeInTheDocument();
    expect(screen.getByText(explanation.disclaimer)).toBeInTheDocument();
    expect(screen.getByText(/ollama · llama3\.1/)).toBeInTheDocument();
    expect(screen.getByText('Numbers verified: 2')).toBeInTheDocument();
  });

  it('dismisses the result panel', () => {
    mockedUseAiStatus.mockReturnValue({
      data: { enabled: true },
      isLoading: false,
      isError: false,
    } as unknown as StatusResult);
    mockedUseExplainInsights.mockReturnValue({
      mutate: vi.fn(),
      data: explanation,
      isPending: false,
      isError: false,
      isSuccess: true,
    } as unknown as ExplainResult);

    renderCard();
    expect(screen.getByText('AI-generated')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Dismiss AI explanation' }));

    expect(screen.queryByText('AI-generated')).not.toBeInTheDocument();
  });

  it('explains AI is disabled for the tenant on a 409, and that an admin can enable it', () => {
    mockedUseAiStatus.mockReturnValue({
      data: { enabled: true },
      isLoading: false,
      isError: false,
    } as unknown as StatusResult);
    mockedUseExplainInsights.mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
      isError: true,
      error: new ApiError(409, {
        title: 'AI disabled',
        detail: 'AI explanations are turned off for this tenant.',
      }),
      isSuccess: false,
    } as unknown as ExplainResult);

    renderCard();

    expect(screen.getByText('AI explanations are off for this tenant')).toBeInTheDocument();
    expect(screen.getByText('AI explanations are turned off for this tenant.')).toBeInTheDocument();
  });

  it('shows an honest 502 upstream error with the problem detail and a working retry', () => {
    mockedUseAiStatus.mockReturnValue({
      data: { enabled: true },
      isLoading: false,
      isError: false,
    } as unknown as StatusResult);
    const mutate = vi.fn();
    mockedUseExplainInsights.mockReturnValue({
      mutate,
      isPending: false,
      isError: true,
      error: new ApiError(502, {
        title: 'Bad Gateway',
        detail: 'AI provider unreachable',
      }),
      isSuccess: false,
    } as unknown as ExplainResult);

    renderCard();

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText('AI provider unreachable')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

    expect(mutate).toHaveBeenCalledWith({ weeks: 12 });
  });
});
