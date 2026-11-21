import { describe, expect, it } from 'vitest';
import { userFromToken } from './roles';

describe('userFromToken', () => {
  it('reads username and realm roles from a Keycloak access token', () => {
    const user = userFromToken({
      sub: 'f1c2',
      preferred_username: 'admin',
      realm_access: { roles: ['ADMIN', 'USER', 'default-roles-spintowin'] },
    });

    expect(user).toEqual({
      id: 'f1c2',
      username: 'admin',
      roles: ['ADMIN', 'USER', 'default-roles-spintowin'],
      isAdmin: true,
    });
  });

  it('is not an admin with only the USER role', () => {
    const user = userFromToken({ sub: 'a1', preferred_username: 'user1', realm_access: { roles: ['USER'] } });

    expect(user.isAdmin).toBe(false);
  });

  it('treats a token without realm_access as having no roles', () => {
    const user = userFromToken({ sub: 'a1' });

    expect(user).toEqual({ id: 'a1', username: 'a1', roles: [], isAdmin: false });
  });
});
