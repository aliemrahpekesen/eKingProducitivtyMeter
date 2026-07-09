import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ApiError } from '../api/client';
import { EmptyState, ErrorState, Loading } from './states';

describe('states', () => {
  it('Loading exposes the label as a live status', () => {
    render(<Loading label="Loading things…" />);
    expect(screen.getByRole('status')).toHaveTextContent('Loading things…');
  });

  it('EmptyState renders the title and hint', () => {
    render(<EmptyState title="Nothing here" hint="do the thing" />);
    expect(screen.getByText('Nothing here')).toBeInTheDocument();
    expect(screen.getByText('do the thing')).toBeInTheDocument();
  });

  it('ErrorState maps a 401 to a no-tenant message', () => {
    render(<ErrorState error={new ApiError(401, { title: 'Tenant required' })} />);
    expect(screen.getByRole('alert')).toHaveTextContent('No tenant resolved');
  });

  it('ErrorState surfaces problem+json title, detail and traceId', () => {
    render(
      <ErrorState
        error={
          new ApiError(400, { title: 'Invalid cursor', detail: 'bad cursor', traceId: 'deadbeef' })
        }
      />,
    );
    expect(screen.getByText('Invalid cursor')).toBeInTheDocument();
    expect(screen.getByText('bad cursor')).toBeInTheDocument();
    expect(screen.getByText(/deadbeef/)).toBeInTheDocument();
  });
});
