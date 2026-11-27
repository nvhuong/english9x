import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { defaultClient } from '../client.js';
import { executeReadOnlyQuery } from '../db.js';

export function registerAuthTools(server: McpServer) {
  server.tool(
    'e9x_auth_login',
    'Authenticate with English9x backend using email and password, setting active session token',
    {
      email: z.string().email().describe('User email address'),
      password: z.string().describe('User password'),
    },
    async ({ email, password }) => {
      try {
        const tokens = await defaultClient.login(email, password);
        const meRes = await defaultClient.getMe();
        return {
          content: [
            {
              type: 'text',
              text: JSON.stringify(
                {
                  success: true,
                  message: 'Successfully authenticated with English9x backend',
                  user: meRes.ok ? meRes.data : null,
                  accessToken: tokens.accessToken.slice(0, 16) + '...',
                },
                null,
                2
              ),
            },
          ],
        };
      } catch (err: any) {
        return {
          content: [{ type: 'text', text: JSON.stringify({ success: false, error: err.message }, null, 2) }],
          isError: true,
        };
      }
    }
  );

  server.tool(
    'e9x_get_context',
    'Get currently authenticated user identity, roles, and organization context',
    {},
    async () => {
      const res = await defaultClient.getMe();
      if (!res.ok) {
        return {
          content: [
            {
              type: 'text',
              text: JSON.stringify(
                {
                  authenticated: false,
                  message: 'No valid active session or backend returned non-200',
                  details: res.data,
                },
                null,
                2
              ),
            },
          ],
        };
      }
      return {
        content: [{ type: 'text', text: JSON.stringify({ authenticated: true, user: res.data }, null, 2) }],
      };
    }
  );

  server.tool(
    'e9x_list_users',
    'List registered users and their roles in the system (read-only query)',
    {
      limit: z.number().int().positive().max(100).optional().default(20).describe('Limit results count'),
      role: z.string().optional().describe('Filter by role name (e.g. LEARNER, TEACHER, ORG_ADMIN, SYS_ADMIN)'),
    },
    async ({ limit, role }) => {
      try {
        let sql = `
          SELECT u.id, u.email, u.display_name, u.status, u.created_at,
                 coalesce(json_agg(r.role_code) FILTER (WHERE r.role_code IS NOT NULL), '[]') as roles
          FROM users u
          LEFT JOIN user_roles r ON r.user_id = u.id
        `;
        const params: any[] = [];
        if (role) {
          sql += ` WHERE EXISTS (SELECT 1 FROM user_roles ur WHERE ur.user_id = u.id AND ur.role_code = $1)`;
          params.push(role);
        }
        sql += ` GROUP BY u.id ORDER BY u.created_at DESC LIMIT $${params.length + 1}`;
        params.push(limit);

        const result = await executeReadOnlyQuery(sql, params, limit);
        return {
          content: [{ type: 'text', text: JSON.stringify(result.rows, null, 2) }],
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
