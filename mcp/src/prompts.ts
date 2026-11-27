import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { z } from 'zod';

export function registerPrompts(server: McpServer) {
  server.prompt(
    'diagnose_learner_weakness',
    'Diagnose a learner weakness radar and prescribe a targeted remedial study roadmap',
    {
      learnerId: z.string().describe('ID or name of the learner'),
      exam: z.string().describe('Exam code (e.g. TOEIC, IELTS)'),
      targetScore: z.string().optional().describe('Target band or score (e.g. 750, Band 7.5)'),
    },
    async ({ learnerId, exam, targetScore }) => {
      return {
        messages: [
          {
            role: 'user',
            content: {
              type: 'text',
              text: `Please analyze learner ${learnerId}'s weakness report for ${exam}${
                targetScore ? ` aiming for ${targetScore}` : ''
              }.

Instructions:
1. Use \`e9x_get_weakness_radar(exam="${exam}")\` to retrieve the learner's lowest accuracy question types.
2. For the top 3 weakest question types, query the relevant courses and lessons using \`e9x_list_courses(exam="${exam}")\`.
3. Provide a structured 4-week remedial plan with concrete daily micro-practice targets and strategies for eliminating recurring error patterns.`,
            },
          },
        ],
      };
    }
  );

  server.prompt(
    'generate_toeic_question',
    'Generate standard TOEIC Part 5 Incomplete Sentence question with explanations and distractors',
    {
      grammarTopic: z.string().describe('Grammar or vocabulary focus (e.g., Gerunds vs Infinitives, Relative Clauses, Conjunctions)'),
      difficultyLevel: z.enum(['easy', 'medium', 'hard']).describe('Target difficulty level'),
    },
    async ({ grammarTopic, difficultyLevel }) => {
      return {
        messages: [
          {
            role: 'user',
            content: {
              type: 'text',
              text: `Generate a high-quality TOEIC Part 5 Incomplete Sentence question focusing on "${grammarTopic}" with difficulty level "${difficultyLevel}".

The output must include:
1. Sentence with a blank (________).
2. Four options (A, B, C, D) with authentic business context.
3. Correct answer key.
4. Detailed Vietnamese grammatical explanation analyzing why the correct option fits and why each distractor is incorrect.
5. JSON payload compatible with English9x \`QuestionRequest\` schema.`,
            },
          },
        ],
      };
    }
  );

  server.prompt(
    'grade_writing_submission',
    'Grade an IELTS or TOEIC Writing essay according to standard CEFR rubrics with actionable feedback',
    {
      taskPrompt: z.string().describe('The writing task prompt / question statement'),
      essayText: z.string().describe('The learner written essay response'),
      exam: z.enum(['IELTS_TASK1', 'IELTS_TASK2', 'TOEIC_WRITING']).describe('Target exam type'),
    },
    async ({ taskPrompt, essayText, exam }) => {
      return {
        messages: [
          {
            role: 'user',
            content: {
              type: 'text',
              text: `Please grade the following ${exam} submission:

---
PROMPT:
${taskPrompt}
---
ESSAY:
${essayText}
---

Provide comprehensive evaluation:
1. Criteria band breakdown:
   - Task Achievement / Response
   - Coherence and Cohesion
   - Lexical Resource
   - Grammatical Range and Accuracy
2. Key strengths and weaknesses.
3. Line-by-line grammatical & lexical corrections.
4. Exemplary rewritten paragraph demonstrating elevated vocabulary and sentence variety.`,
            },
          },
        ],
      };
    }
  );
}
