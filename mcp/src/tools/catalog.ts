import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { defaultClient } from '../client.js';

export function registerCatalogTools(server: McpServer) {
  server.tool(
    'e9x_list_courses',
    'List all published courses in English9x public catalog with chapter and lesson counts',
    {
      exam: z.string().optional().describe('Filter by exam code, e.g. TOEIC, IELTS'),
    },
    async ({ exam }) => {
      const res = await defaultClient.get('/api/catalog/courses', {
        query: { exam },
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
    'e9x_get_course_outline',
    'Get full outline of a course including chapters, lessons, kinds, and pass thresholds',
    {
      code: z.string().describe('Unique 6-character course code, e.g. TOEIC1, IELT01'),
    },
    async ({ code }) => {
      const res = await defaultClient.get(`/api/catalog/courses/${encodeURIComponent(code)}`);
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
    'e9x_search_catalog',
    'Search courses across published catalog by title or description keywords',
    {
      keyword: z.string().describe('Search keyword in course title or description'),
      exam: z.string().optional().describe('Optional exam filter: TOEIC, IELTS'),
    },
    async ({ keyword, exam }) => {
      const res = await defaultClient.get<any[]>('/api/catalog/courses', {
        query: { exam },
      });
      if (!res.ok) {
        return {
          content: [{ type: 'text', text: JSON.stringify({ error: res.data }, null, 2) }],
          isError: true,
        };
      }

      const kw = keyword.toLowerCase();
      const filtered = (res.data || []).filter(
        (c) =>
          (c.title && c.title.toLowerCase().includes(kw)) ||
          (c.description && c.description.toLowerCase().includes(kw)) ||
          (c.code && c.code.toLowerCase().includes(kw))
      );

      return {
        content: [{ type: 'text', text: JSON.stringify({ count: filtered.length, results: filtered }, null, 2) }],
      };
    }
  );

  server.tool(
    'e9x_get_score_table',
    'Get official score conversion tables (raw correct count to scaled score)',
    {
      examCode: z.string().describe('Exam code, e.g. TOEIC, IELTS'),
      skill: z.string().optional().describe('Skill, e.g. LISTENING, READING, WRITING, SPEAKING'),
    },
    async ({ examCode, skill }) => {
      const res = await defaultClient.get('/api/cms/score-tables', {
        query: { examCode, skill },
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
}
