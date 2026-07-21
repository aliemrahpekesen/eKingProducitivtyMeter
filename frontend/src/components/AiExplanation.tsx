// Shared rendering for AI-generated output (M6-B): the Overview "Explain with AI" card and the
// ReportDetail "AI narrative" panel both render through this one component so the AI-generated
// badge, the API's disclaimer, provider/model attribution, and the "Numbers verified" line always
// read identically and can never silently drift between the two call sites. AI is optional,
// per-tenant, off by default — this file only ever renders what the API already returned; it never
// computes or embellishes a number itself.
import { ApiError } from '../api/client';
import type { ExplanationView } from '../api/types';
import { ErrorState } from './states';

export function AiExplanationResult({
  explanation,
  onDismiss,
}: {
  explanation: ExplanationView;
  onDismiss: () => void;
}): JSX.Element {
  return (
    <div className="ai-panel" role="region" aria-label="AI explanation">
      <div className="ai-panel-head">
        <span className="badge badge-ai">AI-generated</span>
        <button
          type="button"
          className="btn ai-dismiss"
          onClick={onDismiss}
          aria-label="Dismiss AI explanation"
        >
          Dismiss
        </button>
      </div>
      <p className="ai-narrative">{explanation.narrative}</p>
      <p className="muted ai-disclaimer">{explanation.disclaimer}</p>
      <p className="muted mono ai-meta">
        {explanation.provider} · {explanation.model}
      </p>
      <p className="muted ai-verified">Numbers verified: {explanation.citedNumbers.length}</p>
    </div>
  );
}

// 409 /problems/ai-disabled is the one AI error with bespoke copy (the tenant turned it off after
// the button was already shown, or a race with an admin flipping the toggle) — everything else
// (502 /problems/ai-upstream, /problems/ai-rejected, …) surfaces through the same honest
// ErrorState every other panel in the app uses, with its problem.detail and a retry button.
export function AiExplainError({
  error,
  onRetry,
}: {
  error: unknown;
  onRetry: () => void;
}): JSX.Element {
  if (error instanceof ApiError && error.status === 409) {
    return (
      <div className="state state-error ai-disabled-error" role="alert">
        <p className="state-title">AI explanations are off for this tenant</p>
        <p className="state-hint">
          {error.problem?.detail ?? 'An admin can turn this on from the Admin panel.'}
        </p>
      </div>
    );
  }
  return <ErrorState error={error} onRetry={onRetry} />;
}
