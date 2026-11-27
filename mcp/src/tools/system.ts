import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { defaultClient } from '../client.js';
import { executeReadOnlyQuery, getDatabaseStats, getDbPool } from '../db.js';

export function registerSystemTools(server: McpServer) {
  server.tool(
    'e9x_health_check',
    'Check health status of English9x backend API and PostgreSQL database',
    {
      checkDatabase: z.boolean().optional().default(true).describe('Whether to also ping PostgreSQL database'),
    },
    async ({ checkDatabase }) => {
      const apiRes = await defaultClient.checkHealth();
      const configRes = await defaultClient.getPublicConfig();

      let dbStatus = 'skipped';
      let dbError: string | null = null;

      if (checkDatabase) {
        try {
          const pool = getDbPool();
          const client = await pool.connect();
          try {
            await client.query('SELECT 1');
            dbStatus = 'healthy';
          } finally {
            client.release();
          }
        } catch (err: any) {
          dbStatus = 'error';
          dbError = err.message;
        }
      }

      const summary = {
        api: {
          healthy: apiRes.ok,
          statusCode: apiRes.status,
          response: apiRes.data,
        },
        publicConfig: configRes.ok ? configRes.data : null,
        database: {
          status: dbStatus,
          error: dbError,
        },
        timestamp: new Date().toISOString(),
      };

      return {
        content: [{ type: 'text', text: JSON.stringify(summary, null, 2) }],
      };
    }
  );

  server.tool(
    'e9x_database_query',
    'Execute a safe, read-only SQL query against the English9x PostgreSQL database (SELECT / EXPLAIN only)',
    {
      sql: z.string().describe('Read-only SQL statement starting with SELECT, EXPLAIN, or WITH'),
      maxRows: z.number().int().positive().max(1000).optional().default(100).describe('Maximum number of rows to return (default 100)'),
    },
    async ({ sql, maxRows }) => {
      try {
        const result = await executeReadOnlyQuery(sql, [], maxRows);
        return {
          content: [
            {
              type: 'text',
              text: JSON.stringify(
                {
                  success: true,
                  rowCount: result.rowCount,
                  fields: result.fields.map((f) => f.name),
                  rows: result.rows,
                },
                null,
                2
              ),
            },
          ],
        };
      } catch (err: any) {
        return {
          content: [
            {
              type: 'text',
              text: JSON.stringify({ success: false, error: err.message }, null, 2),
            },
          ],
          isError: true,
        };
      }
    }
  );

  server.tool(
    'e9x_database_stats',
    'Get table statistics, table names, and estimated row counts in the English9x database',
    {},
    async () => {
      try {
        const stats = await getDatabaseStats();
        return {
          content: [{ type: 'text', text: JSON.stringify(stats, null, 2) }],
        };
      } catch (err: any) {
        return {
          content: [{ type: 'text', text: JSON.stringify({ error: err.message }, null, 2) }],
          isError: true,
        };
      }
    }
  );
}
