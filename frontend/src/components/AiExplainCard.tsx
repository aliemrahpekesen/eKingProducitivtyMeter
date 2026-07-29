// "Explain with AI" — POST /api/v1/insights/explain. AI is optional, per-tenant, OFF by default;
// this card renders ONLY when useAiStatus says the tenant turned it on, and it only ever EXPLAINS
// the deterministic numbers already on screen (Delivery trends, Recommendations, …) — never a new
// metric source, never individual-level (NFR-071). Output is always labeled AI-generated with the
// API's own disclaimer, shown verbatim (see AiExplanation.tsx, shared with ReportDetail).
import { useState } from 'react';
import { useAiStatus, useExplainInsights } from '../api/hooks';
import { useTenant } from '../app/tenantContext';
import { AiExplainError, AiExplanationResult } from './AiExplanation';

// TrendsBoard's week-range selector (4/12/26w) is local component state, not lifted to shared
// context — reaching into it here would mean prop-drilling range state across the whole Overview
// tab for one button. Kept explicit and minimal instead: this card always explains the same
// 12-week window TrendsBoard itself defaults to.
const EXPLAIN_WEEKS = 12;

export function AiExplainCard(): JSX.Element | null {
  const { tenantId } = useTenant();
  const status = useAiStatus(tenantId);
  const explain = useExplainInsights(tenantId);
  const [dismissed, setDismissed] = useState(false);

  if (status.data?.enabled !== true) {
    return null;
  }

  const handleExplain = (): void => {
    setDismissed(false);
    explain.mutate({ weeks: EXPLAIN_WEEKS });
  };

  return (
    <section className="card card-ai-explain" aria-label="AI explanation">
      <header className="card-head">
        <div>
          <h2>AI explanation</h2>
          <span className="card-q">
            Optional — explains the numbers above, computes nothing new
          </span>
        </div>
        <button type="button" className="btn" disabled={explain.isPending} onClick={handleExplain}>
          {explain.isPending ? 'Explaining…' : 'Explain with AI'}
        </button>
      </header>

      {explain.isSuccess && !dismissed && explain.data !== undefined ? (
        <AiExplanationResult explanation={explain.data} onDismiss={() => setDismissed(true)} />
      ) : null}
      {explain.isError && !dismissed ? (
        <AiExplainError error={explain.error} onRetry={handleExplain} />
      ) : null}
    </section>
  );
}
