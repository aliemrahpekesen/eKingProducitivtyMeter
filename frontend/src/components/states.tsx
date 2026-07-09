import { ApiError } from '../api/client';

export function Loading({ label }: { label: string }): JSX.Element {
  return (
    <div className="state state-loading" role="status" aria-live="polite">
      <span className="spinner" aria-hidden="true" />
      <span>{label}</span>
    </div>
  );
}

export function EmptyState({ title, hint }: { title: string; hint?: string }): JSX.Element {
  return (
    <div className="state state-empty">
      <p className="state-title">{title}</p>
      {hint !== undefined ? <p className="state-hint">{hint}</p> : null}
    </div>
  );
}

export function ErrorState({
  error,
  onRetry,
}: {
  error: unknown;
  onRetry?: () => void;
}): JSX.Element {
  const api = error instanceof ApiError ? error : undefined;
  // The backend returns 401 problem+json when no tenant resolves (ApiExceptionHandler); the OpenAPI
  // snapshot only documents 200s today, so this maps the real behavior until DEBT-011 documents it.
  const isNoTenant = api?.status === 401;
  const title = isNoTenant
    ? 'No tenant resolved'
    : (api?.problem?.title ?? (error instanceof Error ? error.message : 'Something went wrong'));
  const detail = isNoTenant
    ? 'The tenant id was rejected — check it and load again.'
    : api?.problem?.detail;

  return (
    <div className="state state-error" role="alert">
      <p className="state-title">{title}</p>
      {detail !== undefined ? <p className="state-hint">{detail}</p> : null}
      {api?.problem?.traceId !== undefined ? (
        <p className="state-trace mono">traceId: {api.problem.traceId}</p>
      ) : null}
      {onRetry !== undefined ? (
        <button type="button" className="btn" onClick={onRetry}>
          Retry
        </button>
      ) : null}
    </div>
  );
}
