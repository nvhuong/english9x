import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { defaultClient } from '../client.js';

export function registerTestsTools(server: McpServer) {
  server.tool(
    'e9x_list_tests',
    'List test structures and versions available for mock exams or practice',
    {
      examCode: z.string().optional().describe('Filter by exam: TOEIC, IELTS'),
      limit: z.number().int().positive().max(100).optional().default(20),
      offset: z.number().int().min(0).optional().default(0),
    },
    async ({ examCode, limit, offset }) => {
      const res = await defaultClient.get('/api/cms/tests', {
        query: { examCode, limit, offset },
      });
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
    'e9x_get_test_details',
    'Get structure, sections, duration, and question groups of a specific test',
    {
      testId: z.string().uuid().describe('Unique identifier (UUID) of the test'),
    },
    async ({ testId }) => {
      const res = await defaultClient.get(`/api/cms/tests/${testId}`);
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
    'e9x_inspect_question',
    'Inspect full question specification including prompt, content, answer key, and explanation (Staff/Admin)',
    {
      questionId: z.string().uuid().describe('Unique UUID of the question'),
    },
    async ({ questionId }) => {
      const res = await defaultClient.get(`/api/cms/questions/${questionId}`);
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
