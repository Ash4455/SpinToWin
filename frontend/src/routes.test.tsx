import { render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { AuthContext, type AuthContextValue } from './auth/AuthProvider';
import { userFromToken } from './auth/roles';
import { routes } from './routes';

function renderAs(roles: string[], path: string) {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation(async () => new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } })),
  );
  const value: AuthContextValue = {
    state: { status: 'authenticated', user: userFromToken({ sub: 's1', preferred_username: 'someone', realm_access: { roles } }) },
    login: vi.fn(),
    logout: vi.fn(),
  };
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(
    <AuthContext.Provider value={value}>
      <RouterProvider router={router} />
    </AuthContext.Provider>,
  );
  return router;
}

describe('admin routes', () => {
  it.each(['/admin', '/admin/campaigns/7'])('send a USER who opens %s back to the wheel', async (path) => {
    const router = renderAs(['USER'], path);

    await waitFor(() => expect(router.state.location.pathname).toBe('/campaigns/1'));
    expect(screen.queryByRole('heading', { name: '管理後台' })).not.toBeInTheDocument();
  });

  it('show the admin console to an ADMIN', async () => {
    const router = renderAs(['ADMIN', 'USER'], '/admin');

    expect(await screen.findByRole('heading', { name: '管理後台' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/admin');
  });
});
