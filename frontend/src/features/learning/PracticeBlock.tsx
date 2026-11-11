import { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useApi } from '../../api/useApi';
import type { AnswerFeedback } from './StrategyLessonView';
import { practiceRequest } from './queries';
import type { PracticeResultData } from './types';

export interface PracticeTally {
  answered: number;
  correct: number;
}

/**
 * Practice for a lesson: the checker {@link StrategyLessonView} calls for its embedded questions (the server shows the answer and the explanation of a question the
 * learner has just answered, nowhere else), and the tally the {@link PracticeScore} shows. `check` is injectable for tests and stories.
 */
export function usePractice(lessonId: string, check?: (questionVersionId: string, answer: string) => Promise<PracticeResultData>) {
  const api = useApi();
  const [tally, setTally] = useState<PracticeTally>({ answered: 0, correct: 0 });
  const request = check ?? practiceRequest(api, lessonId);
  const checkAnswer = useCallback(
    async (questionVersionId: string, answer: string): Promise<AnswerFeedback> => {
      const r = await request(questionVersionId, answer);
      setTally((t) => ({ answered: t.answered + 1, correct: t.correct + (r.correct ? 1 : 0) }));
      return { correct: r.correct, correctAnswer: r.correctAnswer, explanation: r.explanation ?? undefined };
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [lessonId, check],
  );
  return { checkAnswer, tally };
}

/** "Practice: 2 of 3 correct" under the lesson; nothing until the first answer. */
export function PracticeScore({ tally, total }: { tally: PracticeTally; total: number }) {
  const { t } = useTranslation('learning');
  if (total === 0) return null;
  return (
    <p role="status" aria-live="polite" className="font-sans text-sm text-muted">
      {tally.answered === 0 ? t('practice.none', { total }) : t('practice.score', { correct: tally.correct, answered: tally.answered, total })}
    </p>
  );
}
