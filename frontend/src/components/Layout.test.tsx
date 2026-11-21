import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { AuthContext, type AuthContextValue } from '../auth/AuthProvider';
import { userFromToken } from '../auth/roles';
import { Layout } from './Layout';

function renderAs(roles: string[], logout = vi.fn()) {
  const value: AuthContextValue = {
    state: {
      status: 'authenticated',
      user: userFromToken({ sub: 's1', preferred_username: 'someone', realm_access: { roles } }),
    },
    login: vi.fn(),
    logout,
  };
  render(
    <AuthContext.Provider value={value}>
      <MemoryRouter>
        <Layout />
      </MemoryRouter>
    </AuthContext.Provider>,
  );
  return { logout };
}

describe('Layout', () => {
  it('shows the admin entry to an ADMIN', () => {
    renderAs(['ADMIN', 'USER']);

    expect(screen.getByRole('link', { name: '管理後台' })).toHaveAttribute('href', '/admin');
  });

  it('hides the admin entry from a USER', () => {
    renderAs(['USER']);

    expect(screen.queryByRole('link', { name: '管理後台' })).not.toBeInTheDocument();
  });

  it('shows who is logged in and logs out', async () => {
    const { logout } = renderAs(['USER']);

    expect(screen.getByText('someone')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '登出' }));
    expect(logout).toHaveBeenCalledOnce();
  });
});
