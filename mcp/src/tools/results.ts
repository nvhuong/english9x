import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { defaultClient } from '../client.js';

export function registerResultsTools(server: McpServer) {
  server.tool(
    'e9x_get_exam_result',
    'Get full exam score result, per-skill scores (scaled), answer explanations, transcripts, and breakdown',
    {
      attemptId: z.string().uuid().describe('UUID of the submitted exam attempt'),
    },
    async ({ attemptId }) => {
      const res = await defaultClient.get(`/api/results/attempts/${attemptId}`);
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
    'e9x_get_weakness_radar',
    'Get learner weakness analysis, accuracy rate across question types, and targeted remediation lessons',
    {
      exam: z.string().optional().describe('Optional exam filter: TOEIC, IELTS'),
    },
    async ({ exam }) => {
      const res = await defaultClient.get('/api/results/weakness', {
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
    'e9x_get_ai_feedback',
    'Retrieve AI scoring criteria, detailed rubrics, and feedback for Writing/Speaking tasks',
    {
      attemptId: z.string().uuid().describe('UUID of the exam attempt'),
    },
    async ({ attemptId }) => {
      const res = await defaultClient.get(`/api/results/attempts/${attemptId}`);
      if (!res.ok) {
        return {
          content: [{ type: 'text', text: JSON.stringify({ error: res.data }, null, 2) }],
          isError: true,
        };
      }

      const questions = (res.data as any)?.questions || [];
      const subjectiveFeedback = questions.filter(
        (q: any) => q.criteriaFeedback || q.aiScore || q.rubrics || q.comment
      );

      return {
        content: [
          {
            type: 'text',
            text: JSON.stringify(
              {
                attemptId,
                totalGradedQuestions: subjectiveFeedback.length,
                feedback: subjectiveFeedback.length > 0 ? subjectiveFeedback : (res.data as any)?.skills,
              },
              null,
              2
            ),
          },
        ],
      };
    }
  );
}
