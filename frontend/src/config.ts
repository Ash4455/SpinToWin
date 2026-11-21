/** 建置時注入的設定（Vite 的 VITE_* 環境變數，見 .env.example）。 */
const env = import.meta.env;

export const config = {
  /** 同源路徑：compose 由 Nginx、本機開發由 Vite dev server 代理到 gateway */
  apiBaseUrl: env.VITE_API_BASE_URL || '/api/v1',
  keycloak: {
    url: env.VITE_KEYCLOAK_URL || 'http://localhost:8080',
    realm: env.VITE_KEYCLOAK_REALM || 'spintowin',
    clientId: env.VITE_KEYCLOAK_CLIENT_ID || 'spintowin-frontend',
  },
  /** 首頁顯示的 Campaign（目前沒有「列出上線中活動」的 API） */
  defaultCampaignId: env.VITE_DEFAULT_CAMPAIGN_ID || '1',
} as const;
