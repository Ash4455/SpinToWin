import { getAccessToken, handleUnauthorized } from '../auth/keycloak';
import { config } from '../config';
import { createApiClient } from './client';

/** App 共用的 API client：帶目前使用者的 Bearer token。各功能的 API 模組（campaigns.ts…）都用它。 */
export const api = createApiClient({
  baseUrl: config.apiBaseUrl,
  getToken: getAccessToken,
  onUnauthorized: handleUnauthorized,
});
