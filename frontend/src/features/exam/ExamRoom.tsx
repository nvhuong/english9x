import { useQuery } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Dialog, QuestionNavigator } from '../../design';
import { publicKey } from '../../app/stores/context';
import { ExamImage } from './ExamImage';
import { ListeningPlayer } from './ListeningPlayer';
import { QuestionInput } from './QuestionInput';
import { ReadingSplitView } from './ReadingSplitView';
import { SaveStatus } from './SaveStatus';
import { SectionTimer } from './SectionTimer';
import { SpeakingRecorder } from './SpeakingRecorder';
import { DEFAULT_AUTOSAVE_MS, autosaveIntervalMs, examApi } from './examApi';
import { isAnswered, kindOfQuestion, text } from './kinds';
import { ServerClock } from './serverClock';
import type { ExamAttemptView, ExamGroup, ExamQuestion } from './types';
import { useAnswerSync } from './useAnswerSync';

interface Flat {
  q: ExamQuestion;
  g: ExamGroup;
}

const isTyping = (t: EventTarget | null) => {
  const el = t as HTMLElement | null;
  if (!el) return false;
  return el.tagName === 'TEXTAREA' || el.isContentEditable || (el.tagName === 'INPUT' && !['radio', 'checkbox', 'button'].includes((el as HTMLInputElement).type)) || el.tagName === 'SELECT';
};

function useSmallScreen(): boolean {
  const [small, setSmall] = useState(() => typeof window !== 'undefined' && window.matchMedia?.('(max-width: 767px)').matches);
  useEffect(() => {
    const m = window.matchMedia?.('(max-width: 767px)');
    if (!m) return;
    const on = () => setSmall(m.matches);
    m.addEventListener('change', on);
    return () => m.removeEventListener('change', on);
  }, []);
  return small;
}

function flagStore(attemptId: string) {
  const key = `e9x.exam.flags.${attemptId}`;
  return {
    read(): Set<string> {
      try {
        return new Set(JSON.parse(sessionStorage.getItem(key) ?? '[]') as string[]);
      } catch {
        return new Set();
      }
    },
    write(s: Set<string>) {
      try {
        sessionStorage.setItem(key, JSON.stringify([...s]));
      } catch {
        /* flags are only a convenience */
      }
    },
  };
}

export interface ExamRoomProps {
  view: ExamAttemptView;
  onView: (v: ExamAttemptView) => void;
  onRefresh: () => void;
}

/** The room for the CURRENT section of an attempt. Remounted (keyed) when the section changes. */
export function ExamRoom({ view, onView, onRefresh }: ExamRoomProps) {
  const { t } = useTranslation('exam');
  const section = view.currentSection!;
  const interval = useQuery({ queryKey: publicKey('exam', 'autosave-interval'), queryFn: autosaveIntervalMs, staleTime: Infinity, retry: false });
  const clock = useMemo(() => new ServerClock(view.serverNow), [view.serverNow]);
  const sync = useAnswerSync({ attemptId: view.attemptId, clock, intervalMs: interval.data ?? DEFAULT_AUTOSAVE_MS, saved: view.saved });
  const flat = useMemo<Flat[]>(() => section.groups.flatMap((g) => g.questions.map((q) => ({ q, g }))), [section]);
  const [index, setIndex] = useState(0);
  const [flags, setFlags] = useState<Set<string>>(() => flagStore(view.attemptId).read());
  const [recorded, setRecorded] = useState<Set<string>>(new Set());
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [warn, setWarn] = useState<string | null>(null);
  const [dismissed, setDismissed] = useState(false);
  const small = useSmallScreen();

  const cur = flat[Math.min(index, flat.length - 1)];
  const last = view.sections.findIndex((s) => s.sectionId === section.sectionId) === view.sections.length - 1;
  const recommend = small && !dismissed && view.examCode === 'IELTS' && (section.skill === 'READING' || section.skill === 'WRITING');

  const go = useCallback(
    (to: number) => {
      if (to < 0 || to >= flat.length) return;
      void sync.flush();
      setIndex(to);
    },
    [flat.length, sync],
  );
  const toggleFlag = useCallback(() => {
    if (!cur) return;
    setFlags((f) => {
      const next = new Set(f);
      if (!next.delete(cur.q.questionId)) next.add(cur.q.questionId);
      flagStore(view.attemptId).write(next);
      return next;
    });
  }, [cur, view.attemptId]);

  // A/B/C/D, left/right, F. Captured before the browser: arrows must not move a radio button's selection.
  useEffect(() => {
    if (!cur) return;
    const kind = kindOfQuestion(cur.q.typeCode, cur.q.content);
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey || isTyping(e.target)) return;
      if (e.key === 'ArrowRight') {
        e.preventDefault();
        go(index + 1);
      } else if (e.key === 'ArrowLeft') {
        e.preventDefault();
        go(index - 1);
      } else if (e.key === 'f' || e.key === 'F') {
        e.preventDefault();
        toggleFlag();
      } else if (/^[a-dA-D]$/.test(e.key) && (kind === 'MCQ_SINGLE' || kind === 'GAP_SELECT' || kind === 'MCQ_MULTI')) {
        const key = e.key.toUpperCase();
        const opts = Array.isArray(cur.q.content.options) ? cur.q.content.options : [];
        if (!opts.some((o) => o && typeof o === 'object' && !Array.isArray(o) && String(o.key).toUpperCase() === key)) return;
        e.preventDefault();
        if (kind === 'MCQ_MULTI') {
          const now = Array.isArray(sync.values[cur.q.questionId]) ? (sync.values[cur.q.questionId] as string[]) : [];
          sync.setValue(cur.q.questionId, now.includes(key) ? now.filter((k) => k !== key) : [...now, key]);
        } else sync.setValue(cur.q.questionId, key);
      }
    };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [cur, go, index, sync, toggleFlag]);

  const finish = async () => {
    setBusy(true);
    setNotice(null);
    try {
      const ok = await sync.flush();
      if (!ok) {
        setNotice(t('finish.unsaved'));
        return;
      }
      if (last) {
        await examApi.submit(view.attemptId);
        onView(await examApi.get(view.attemptId));
      } else onView(await examApi.finishSection(view.attemptId, section.sectionId));
      setConfirm(false);
    } catch {
      setNotice(t('finish.failed'));
    } finally {
      setBusy(false);
    }
  };

  const expire = async () => {
    await sync.flush();
    onRefresh();
  };

  if (!cur) return <p role="status" className="p-6">{t('empty')}</p>;
  const kind = kindOfQuestion(cur.q.typeCode, cur.q.content);
  const audioId = text(cur.q.content.audioMediaId) || text(cur.g.content.audioMediaId);
  const imageId = text(cur.q.content.imageMediaId) || text(cur.g.content.imageMediaId);
  const passage = text(cur.g.content.passage);
  const speakSeconds = typeof cur.q.content.speakSeconds === 'number' ? cur.q.content.speakSeconds : 120;
  const prepareSeconds = typeof cur.q.content.prepareSeconds === 'number' ? cur.q.content.prepareSeconds : 0;

  const body = (
    <div className="flex flex-col gap-4">
      {text(cur.g.content.title) && <h2 className="font-serif text-xl font-bold text-ink">{text(cur.g.content.title)}</h2>}
      {text(cur.g.content.instructions) && <p className="text-muted">{text(cur.g.content.instructions)}</p>}
      {audioId && <ListeningPlayer key={audioId} attemptId={view.attemptId} mediaId={audioId} />}
      {imageId && <ExamImage key={imageId} mediaId={imageId} />}
      {kind === 'SPEAKING_RESPONSE' && <QuestionInput kind={kind} content={cur.q.content} value={undefined} onChange={() => undefined} number={index + 1} />}
      {kind === 'SPEAKING_RESPONSE' ? (
        <SpeakingRecorder
          key={cur.q.questionId}
          speakSeconds={speakSeconds}
          prepareSeconds={prepareSeconds}
          upload={(blob) => examApi.recording(view.attemptId, cur.q.questionId, blob)}
          onUploaded={() => setRecorded((r) => new Set(r).add(cur.q.questionId))}
        />
      ) : (
        <QuestionInput kind={kind} content={cur.q.content} value={sync.values[cur.q.questionId]} onChange={(v) => sync.setValue(cur.q.questionId, v)} number={index + 1} />
      )}
    </div>
  );

  return (
    <div className="mx-auto flex max-w-6xl flex-col gap-4 p-4">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="font-serif text-2xl font-bold text-ink">{view.title ?? view.examCode}</h1>
          <p className="text-sm text-muted">
            {t('section.of', { n: view.sections.findIndex((s) => s.sectionId === section.sectionId) + 1, total: view.sections.length })} · {section.skill ?? section.sectionId}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <SaveStatus state={sync.state} pending={sync.pending} />
          <SectionTimer endsAt={section.endsAt} serverNow={view.serverNow} onResync={onRefresh} onExpire={() => void expire()} onWarn={(l) => setWarn(t(l === 'warn5' ? 'warn.five' : 'warn.one'))} />
        </div>
      </header>
      {warn && <p role="alert" className="rounded-md border border-edge bg-sunken p-2 font-semibold text-ink">{warn}</p>}
      {recommend && (
        <div role="note" className="flex items-center justify-between gap-3 rounded-md border border-edge bg-sunken p-3 text-ink">
          <span>{t('small.recommend')}</span>
          <Button variant="secondary" size="sm" onClick={() => setDismissed(true)}>{t('small.dismiss')}</Button>
        </div>
      )}
      <div className="grid gap-6 lg:grid-cols-[1fr_16rem]">
        <section aria-label={t('question.region')} className="min-w-0 rounded-lg border border-line bg-surface p-5">
          {passage ? <ReadingSplitView passage={passage} title={undefined}>{body}</ReadingSplitView> : body}
          <div className="mt-6 flex flex-wrap items-center gap-2">
            <Button variant="secondary" onClick={() => go(index - 1)} disabled={index === 0}>{t('nav.prev')}</Button>
            <Button variant="secondary" onClick={() => go(index + 1)} disabled={index >= flat.length - 1}>{t('nav.next')}</Button>
            <Button variant="ghost" aria-pressed={flags.has(cur.q.questionId)} onClick={toggleFlag}>
              {flags.has(cur.q.questionId) ? t('nav.unflag') : t('nav.flag')}
            </Button>
          </div>
        </section>
        <aside className="flex flex-col gap-4">
          <QuestionNavigator
            questions={flat.map((f, i) => ({ id: f.q.questionId, number: i + 1, answered: isAnswered(sync.values[f.q.questionId]) || recorded.has(f.q.questionId), flagged: flags.has(f.q.questionId) }))}
            currentId={cur.q.questionId}
            onSelect={(id) => go(flat.findIndex((f) => f.q.questionId === id))}
          />
          <p className="text-xs text-muted">{t('keys.help')}</p>
          <Button variant={last ? 'accent' : 'primary'} onClick={() => setConfirm(true)}>{last ? t('finish.submit') : t('finish.section')}</Button>
        </aside>
      </div>
      <Dialog
        open={confirm}
        onOpenChange={setConfirm}
        title={last ? t('finish.submitTitle') : t('finish.sectionTitle')}
        description={last ? t('finish.submitHelp') : t('finish.sectionHelp')}
        footer={
          <>
            <Button variant="secondary" onClick={() => setConfirm(false)}>{t('finish.cancel')}</Button>
            <Button variant="accent" loading={busy} onClick={() => void finish()}>{t('finish.confirm')}</Button>
          </>
        }
      >
        {notice && <p role="alert" className="text-danger">{notice}</p>}
      </Dialog>
    </div>
  );
}
