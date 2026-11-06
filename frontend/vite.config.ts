import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Dev: the Spring Boot app runs on 8080 (mvn spring-boot:run or compose).
    proxy: { '/api': 'http://localhost:8080', '/actuator': 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    globals: false,
  },
});
