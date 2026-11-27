import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';
import { defaultClient } from '../client.js';

export function registerExamTools(server: McpServer) {
  server.tool(
    'e9x_create_attempt',
    'Start a new exam attempt on a test version for the authenticated learner',
    {
      testVersionId: z.string().uuid().describe('UUID of the published test version to take'),
    },
    async ({ testVersionId }) => {
      const res = await defaultClient.post('/api/exam/attempts', { testVersionId });
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
    'e9x_get_attempt_state',
    'Get current state, remaining time, sections, and questions of an ongoing exam attempt',
    {
      attemptId: z.string().uuid().describe('UUID of the exam attempt'),
    },
    async ({ attemptId }) => {
      const res = await defaultClient.get(`/api/exam/attempts/${attemptId}`);
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
    'e9x_save_answers',
    'Save learner response to a specific question inside an ongoing attempt',
    {
      attemptId: z.string().uuid().describe('UUID of the attempt'),
      questionId: z.string().uuid().describe('UUID of the question'),
      selectedOption: z.string().optional().describe('Selected option letter (A, B, C, D) for multiple choice'),
      textAnswer: z.string().optional().describe('Text answer for short response / fill-in-blank / essay'),
      rawResponse: z.record(z.any()).optional().describe('Custom arbitrary response payload matching question schema'),
    },
    async ({ attemptId, questionId, selectedOption, textAnswer, rawResponse }) => {
      const body = rawResponse || {
        selected: selectedOption,
        text: textAnswer,
        answeredAt: new Date().toISOString(),
      };
      const res = await defaultClient.put(
        `/api/exam/attempts/${attemptId}/responses/${questionId}`,
        body
      );
      if (!res.ok) {
        return {
          content: [{ type: 'text', text: JSON.stringify({ error: res.data }, null, 2) }],
          isError: true,
        };
      }
      return {
        content: [
          {
            type: 'text',
            text: JSON.stringify(
              {
                success: true,
                message: `Answer saved for question ${questionId}`,
                statusCode: res.status,
              },
              null,
              2
            ),
          },
        ],
      };
    }
  );

  server.tool(
    'e9x_submit_attempt',
    'Submit the completed exam attempt to trigger automatic grading and generate scorecard',
    {
      attemptId: z.string().uuid().describe('UUID of the exam attempt to submit'),
    },
    async ({ attemptId }) => {
      const res = await defaultClient.post(`/api/exam/attempts/${attemptId}/submit`);
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
