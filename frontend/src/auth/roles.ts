/** Keycloak realm roles（與後端 hasRole('ADMIN') / hasRole('USER') 對應）。 */
export const Role = {
  ADMIN: 'ADMIN',
  USER: 'USER',
} as const;

export interface AuthUser {
  /** token 的 sub，也是後端使用的使用者 id */
  id: string;
  username: string;
  roles: string[];
  isAdmin: boolean;
}

/** Keycloak access token 中我們用到的欄位。 */
export interface KeycloakTokenClaims {
  sub?: string;
  preferred_username?: string;
  realm_access?: { roles?: string[] };
}

export function userFromToken(token: KeycloakTokenClaims): AuthUser {
  const id = token.sub ?? '';
  const roles = token.realm_access?.roles ?? [];
  return {
    id,
    username: token.preferred_username ?? id,
    roles,
    isAdmin: roles.includes(Role.ADMIN),
  };
}
