import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  use: {
    baseURL: 'http://localhost:8080',
    headless: true,
  },
  webServer: {
    command: 'echo "server assumed running"',
    url: 'http://localhost:8080/api/health',
    reuseExistingServer: true,
    timeout: 5_000,
  },
});
