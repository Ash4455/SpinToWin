import Keycloak from 'keycloak-js';
import { config } from '../config';
import { userFromToken, type AuthUser, type KeycloakTokenClaims } from './roles';

/**
 * 唯一的 keycloak-js 實例：Authorization Code + PKCE（S256），public client `spintowin-frontend`。
 * 其他程式只透過本模組的函式與 AuthProvider 使用登入狀態，不直接碰 keycloak-js。
 */
const keycloak = new Keycloak({
  url: config.keycloak.url,
  realm: config.keycloak.realm,
  clientId: config.keycloak.clientId,
});

/** access token 剩不到這麼多秒就先換新 */
const MIN_TOKEN_VALIDITY_SECONDS = 30;

type Listener = () => void;
const listeners = new Set<Listener>();
const notify = () => listeners.forEach((l) => l());

keycloak.onAuthSuccess = notify;
keycloak.onAuthRefreshSuccess = notify;
keycloak.onAuthLogout = notify;
keycloak.onAuthRefreshError = () => {
  keycloak.clearToken();
};
// token 到期前自動更新（使用者沒有操作時也保持登入）
keycloak.onTokenExpired = () => {
  keycloak.updateToken(MIN_TOKEN_VALIDITY_SECONDS).catch(() => keycloak.clearToken());
};

let initPromise: Promise<boolean> | undefined;

/**
 * 初始化（只做一次）：check-sso 會整頁導向 Keycloak（prompt=none）確認是否已有 SSO session，
 * 沒有就回到網站並維持未登入，由登入頁讓使用者按「登入」。
 */
export function initAuth(): Promise<boolean> {
  initPromise ??= keycloak.init({
    onLoad: 'check-sso',
    pkceMethod: 'S256',
    checkLoginIframe: false,
  });
  return initPromise;
}

export function subscribeAuth(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function currentUser(): AuthUser | null {
  if (!keycloak.authenticated || !keycloak.tokenParsed) return null;
  return userFromToken(keycloak.tokenParsed as KeycloakTokenClaims);
}

/** 導向 Keycloak 登入頁，登入後回到目前頁面。 */
export function login(): Promise<void> {
  return keycloak.login({ redirectUri: window.location.href });
}

/** 結束 Keycloak session 並回到網站首頁。 */
export function logout(): Promise<void> {
  return keycloak.logout({ redirectUri: `${window.location.origin}/` });
}

/** 有效的 access token（必要時先更新）；未登入時回傳 undefined。給 API client 使用。 */
export async function getAccessToken(): Promise<string | undefined> {
  if (!keycloak.authenticated) return undefined;
  try {
    await keycloak.updateToken(MIN_TOKEN_VALIDITY_SECONDS);
  } catch {
    keycloak.clearToken();
    return undefined;
  }
  return keycloak.token;
}

/** API 回 401：丟掉本地 token，畫面回到登入頁（不自動導向，避免 token 被拒時無限重導）。 */
export function handleUnauthorized(): void {
  if (keycloak.authenticated) keycloak.clearToken();
}
