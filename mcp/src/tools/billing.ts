import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { defaultClient } from '../client.js';

export function registerBillingTools(server: McpServer) {
  server.tool(
    'e9x_list_plans',
    'List subscription plans available for learners (or all plans for administrators)',
    {
      adminView: z.boolean().optional().default(false).describe('Whether to fetch all plans including hidden/inactive ones (Requires Admin)'),
    },
    async ({ adminView }) => {
      const endpoint = adminView ? '/api/admin/plans' : '/api/plans';
      const res = await defaultClient.get(endpoint);
      if (!res.ok) {
        return {
          content: [{ type: 'text', text: JSON.stringify({ error: res.data }, null, 2) }],
          isError: true,
        };
      }
      return {
        content: [{ type: 'text', text: JSON.stringify(res.data, null, 2) }],
      };
    }
  );

  server.tool(
    'e9x_list_orders',
    'List orders and payment transactions for the authenticated learner or organization',
    {
      code: z.string().optional().describe('Optional order code to view a single specific order'),
    },
    async ({ code }) => {
      const endpoint = code ? `/api/orders/${encodeURIComponent(code)}` : '/api/orders';
      const res = await defaultClient.get(endpoint);
      if (!res.ok) {
        return {
          content: [{ type: 'text', text: JSON.stringify({ error: res.data }, null, 2) }],
          isError: true,
        };
      }
      return {
        content: [{ type: 'text', text: JSON.stringify(res.data, null, 2) }],
      };
    }
  );

  server.tool(
    'e9x_get_licenses',
    'List organization B2B enterprise licenses and seat allocations',
    {
      orgId: z.string().uuid().optional().describe('UUID of the organization (if omitted, lists all licenses via sysadmin)'),
    },
    async ({ orgId }) => {
      const endpoint = orgId ? `/api/orgs/${orgId}/licenses` : '/api/admin/org-licenses';
      const res = await defaultClient.get(endpoint);
      if (!res.ok) {
        return {
          content: [{ type: 'text', text: JSON.stringify({ error: res.data }, null, 2) }],
          isError: true,
        };
      }
      return {
        content: [{ type: 'text', text: JSON.stringify(res.data, null, 2) }],
      };
    }
  );
}
