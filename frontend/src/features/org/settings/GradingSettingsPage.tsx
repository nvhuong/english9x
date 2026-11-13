import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiProblemError } from '../../../api/problem';
import { ForbiddenPage } from '../../../app/pages/ForbiddenPage';
import { NotFoundPage } from '../../../app/pages/NotFoundPage';
import { useContextStore } from '../../../app/stores/context';
import { Button, Select } from '../../../design';
import { FormAlert, FormNotice } from '../../auth/components';
import { useOrgStore } from '../orgContext';
import { useGradingSettings, useSaveGradingSettings, type GradingSettingsView } from './queries';

type Grader = 'CENTER' | 'ENGLISH9X';
type Mode = 'AI' | 'TEACHER' | 'BOTH';
const MODES: Mode[] = ['AI', 'TEACHER', 'BOTH'];

/** /org/grading: which side grades the centre's learners (its own teachers or English9x) and the Writing/Speaking mode (F50). */
export default function GradingSettingsPage() {
  const orgId = useContextStore((s) => s.orgId);
  const type = useOrgStore((s) => s.orgs.find((o) => o.id === orgId)?.type);
  if (!orgId) return <NotFoundPage />;
  return <Settings orgId={orgId} enterprise={type === 'ENTERPRISE'} />;
}

export function Settings({ orgId, enterprise }: { orgId: string; enterprise: boolean }) {
  const { t } = useTranslation('gradingsettings');
  const settings = useGradingSettings(orgId);
  if (settings.isError && settings.error instanceof ApiProblemError && (settings.error.status === 403 || settings.error.status === 404)) return <ForbiddenPage />;
  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('title')}</h1>
        <p className="mt-2 max-w-prose text-muted">{t('description')}</p>
      </header>
      {settings.isPending && (
        <p role="status" className="text-muted">
          {t('loading')}
        </p>
      )}
      {settings.isError && <FormAlert>{t('errors.loadFailed')}</FormAlert>}
      {settings.data && <SettingsForm orgId={orgId} enterprise={enterprise} initial={settings.data} />}
    </div>
  );
}

function SettingsForm({ orgId, enterprise, initial }: { orgId: string; enterprise: boolean; initial: GradingSettingsView }) {
  const { t } = useTranslation('gradingsettings');
  const save = useSaveGradingSettings(orgId);
  const [grader, setGrader] = useState<Grader>(initial.grader as Grader);
  const [mode, setMode] = useState<Mode>((initial.mode as Mode | undefined) ?? 'AI');
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const onSave = () => {
    setNotice(null);
    setError(null);
    // an enterprise's learners are always graded by English9x in AI mode: the page never offers anything else (and the server refuses it with a 400)
    const body = enterprise ? { grader: 'ENGLISH9X' as const, mode: 'AI' as const } : { grader, mode };
    save.mutate(body, {
      onSuccess: () => setNotice(t('saved')),
      onError: (e) => setError(e instanceof ApiProblemError && e.status === 400 ? t('errors.notAllowed') : t('errors.failed')),
    });
  };

  return (
    <>
      {(
        <form
          aria-label={t('title')}
          className="flex max-w-xl flex-col gap-4"
          onSubmit={(e) => {
            e.preventDefault();
            onSave();
          }}
        >
          <Select
            label={t('grader.label')}
            value={enterprise ? 'ENGLISH9X' : grader}
            onValueChange={(v) => setGrader(v as Grader)}
            disabled={enterprise}
            options={[
              { value: 'ENGLISH9X', label: t('grader.ENGLISH9X') },
              { value: 'CENTER', label: t('grader.CENTER'), disabled: enterprise },
            ]}
          />
          <Select
            label={t('mode.label')}
            value={enterprise ? 'AI' : mode}
            onValueChange={(v) => setMode(v as Mode)}
            disabled={enterprise}
            options={MODES.map((m) => ({ value: m, label: t(`mode.${m}`), disabled: enterprise && m !== 'AI' }))}
          />
          {enterprise ? (
            <p data-testid="enterprise-lock" className="text-sm text-muted">
              {t('enterpriseLocked')}
            </p>
          ) : (
            <p className="text-sm text-muted">{t(`graderHint.${grader}`)}</p>
          )}
          {!enterprise && mode !== 'AI' && <p className="text-sm text-muted">{t('modeHint')}</p>}
          {error && <FormAlert>{error}</FormAlert>}
          {notice && <FormNotice>{notice}</FormNotice>}
          <div>
            <Button type="submit" loading={save.isPending}>
              {t('save')}
            </Button>
          </div>
        </form>
      )}
    </>
  );
}
