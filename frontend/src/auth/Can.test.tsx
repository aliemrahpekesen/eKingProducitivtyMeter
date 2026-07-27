import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useSession } from '../api/hooks';
import { Can } from './Can';

// <Can> reads the caller's effective permissions from the tenant-keyed /session query. Mock both
// the query hook (the permission source) and the tenant context (its cache key) so each case can
// drive a specific effectivePermissions payload without a real network/provider.
vi.mock('../api/hooks', () => ({ useSession: vi.fn() }));
vi.mock('../app/tenantContext', () => ({
  useTenant: () => ({ tenantId: 't-1', setTenantId: () => {} }),
}));

const sessionWith = (effectivePermissions: string[] | undefined) =>
  ({ data: effectivePermissions === undefined ? undefined : { effectivePermissions } }) as never;

describe('Can', () => {
  it('renders its children when the effective set contains the permission', () => {
    vi.mocked(useSession).mockReturnValue(sessionWith(['tenant.manage', 'dashboard.view']));
    render(
      <Can permission="tenant.manage">
        <button type="button">Create tenant</button>
      </Can>,
    );
    expect(screen.getByRole('button', { name: 'Create tenant' })).toBeInTheDocument();
  });

  it('hides its children when the effective set lacks the permission', () => {
    vi.mocked(useSession).mockReturnValue(sessionWith(['dashboard.view']));
    render(
      <Can permission="tenant.manage">
        <button type="button">Create tenant</button>
      </Can>,
    );
    expect(screen.queryByRole('button', { name: 'Create tenant' })).not.toBeInTheDocument();
  });

  // Narrow, deliberate fail-open: session not yet loaded / no tenant / errored → list is genuinely
  // absent, so the control shows (the backend interceptor still enforces with a 403 on the request).
  it('fails open when the permission list is genuinely absent', () => {
    vi.mocked(useSession).mockReturnValue(sessionWith(undefined));
    render(
      <Can permission="platform.operate">
        <button type="button">Rotate KMS key</button>
      </Can>,
    );
    expect(screen.getByRole('button', { name: 'Rotate KMS key' })).toBeInTheDocument();
  });
});
