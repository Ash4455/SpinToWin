import react from '@vitejs/plugin-react';
import { loadEnv } from 'vite';
import { defineConfig } from 'vitest/config';

// 測試以固定時區執行，日期顯示的斷言才不受執行環境影響
process.env.TZ ??= 'Asia/Taipei';

export default defineConfig(({ mode }) => {
  // 本機開發：`npm run dev` 在 http://localhost:5173，/api 代理到 gateway（GATEWAY_URL，預設 http://localhost:8000）
  const env = loadEnv(mode, process.cwd(), '');
  const gatewayUrl = env.GATEWAY_URL || 'http://localhost:8000';

  return {
    plugins: [react()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': { target: gatewayUrl, changeOrigin: true },
      },
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      restoreMocks: true,
      unstubGlobals: true,
    },
  };
});
