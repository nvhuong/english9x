import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { defaultClient } from '../client.js';

export function registerClassroomTools(server: McpServer) {
  server.tool(
    'e9x_list_classes',
    'List classes or student cohorts in an organization (Centres / Enterprises)',
    {
      orgId: z.string().uuid().describe('UUID of the organization'),
    },
    async ({ orgId }) => {
      const res = await defaultClient.get(`/api/orgs/${orgId}/classes`);
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
    'e9x_get_class_details',
    'Get class details including teacher assignment and associated course packages',
    {
      orgId: z.string().uuid().describe('UUID of the organization'),
      classId: z.string().uuid().describe('UUID of the class'),
    },
    async ({ orgId, classId }) => {
      const res = await defaultClient.get(`/api/orgs/${orgId}/classes/${classId}`);
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
    'e9x_get_class_members',
    'Get roster of enrolled students and assigned teachers in a specific class',
    {
      orgId: z.string().uuid().describe('UUID of the organization'),
      classId: z.string().uuid().describe('UUID of the class'),
    },
    async ({ orgId, classId }) => {
      const res = await defaultClient.get(`/api/orgs/${orgId}/classes/${classId}/members`);
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
