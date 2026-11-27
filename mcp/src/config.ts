import dotenv from 'dotenv';
import path from 'node:path';
import fs from 'node:fs';

// Try loading infra/.env if exists, or local .env
const infraEnvPath = path.resolve(process.cwd(), '../infra/.env');
if (fs.existsSync(infraEnvPath)) {
  dotenv.config({ path: infraEnvPath });
} else {
  dotenv.config();
}

export interface McpConfig {
  apiUrl: string;
  postgresUrl: string;
  adminEmail?: string;
  adminPassword?: string;
  authToken?: string;
  port?: number;
}

export const config: McpConfig = {
  apiUrl: process.env.E9X_API_URL || process.env.APP_URL || 'http://localhost:8080',
  postgresUrl:
    process.env.E9X_POSTGRES_URL ||
    process.env.DATABASE_URL ||
    `postgresql://${process.env.POSTGRES_USER || 'e9x'}:${process.env.POSTGRES_PASSWORD || 'e9x_dev_password'}@${
      process.env.POSTGRES_HOST || 'localhost'
    }:${process.env.POSTGRES_PORT || '5432'}/${process.env.POSTGRES_DB || 'e9x'}`,
  adminEmail: process.env.E9X_ADMIN_EMAIL || 'admin@english9x.com',
  adminPassword: process.env.E9X_ADMIN_PASSWORD || 'English9x@2025!',
  authToken: process.env.E9X_AUTH_TOKEN,
  port: process.env.MCP_PORT ? parseInt(process.env.MCP_PORT, 10) : undefined,
};
