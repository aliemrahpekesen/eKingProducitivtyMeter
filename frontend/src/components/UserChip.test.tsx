import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useAuth } from '../auth/authContext';
import { UserChip } from './UserChip';

vi.mock('../auth/authContext', () => ({
  useAuth: vi.fn(),
}));
const mockedUseAuth = vi.mocked(useAuth);

describe('UserChip', () => {
  it('shows the signed-in profile name', () => {
    mockedUseAuth.mockReturnValue({ mode: 'OIDC', profileName: 'Ada Lovelace', logout: vi.fn() });

    render(<UserChip />);

    expect(screen.getByLabelText('Signed in as')).toHaveTextContent('Ada Lovelace');
  });

  it('falls back to a generic label when the profile has no display name', () => {
    mockedUseAuth.mockReturnValue({ mode: 'OIDC', profileName: null, logout: vi.fn() });

    render(<UserChip />);

    expect(screen.getByLabelText('Signed in as')).toHaveTextContent('Signed in');
  });

  it('calls logout when Sign out is clicked', () => {
    const logout = vi.fn();
    mockedUseAuth.mockReturnValue({ mode: 'OIDC', profileName: 'Ada Lovelace', logout });

    render(<UserChip />);
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(logout).toHaveBeenCalled();
  });
});
