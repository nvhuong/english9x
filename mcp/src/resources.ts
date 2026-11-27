import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { defaultClient } from './client.js';
import { getDatabaseStats } from './db.js';

export function registerResources(server: McpServer) {
  server.resource(
    'e9x-system-status',
    'e9x://system/status',
    async (uri) => {
      const ping = await defaultClient.checkHealth();
      const config = await defaultClient.getPublicConfig();
      const payload = {
        uri: uri.href,
        status: ping.ok ? 'ONLINE' : 'OFFLINE',
        backend: ping.data,
        config: config.ok ? config.data : null,
        timestamp: new Date().toISOString(),
      };
      return {
        contents: [
          {
            uri: uri.href,
            mimeType: 'application/json',
            text: JSON.stringify(payload, null, 2),
          },
        ],
      };
    }
  );

  server.resource(
    'e9x-catalog-courses',
    'e9x://catalog/courses',
    async (uri) => {
      const res = await defaultClient.get('/api/catalog/courses');
      return {
        contents: [
          {
            uri: uri.href,
            mimeType: 'application/json',
            text: JSON.stringify(res.data || [], null, 2),
          },
        ],
      };
    }
  );

  server.resource(
    'e9x-schema-database',
    'e9x://schema/database',
    async (uri) => {
      let stats: any = null;
      try {
        stats = await getDatabaseStats();
      } catch (err: any) {
        stats = { error: err.message };
      }
      return {
        contents: [
          {
            uri: uri.href,
            mimeType: 'application/json',
            text: JSON.stringify(stats, null, 2),
          },
        ],
      };
    }
  );
}
