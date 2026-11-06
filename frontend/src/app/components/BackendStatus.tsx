import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

type State = 'checking' | 'up' | 'down';

/** Tiny health probe of the Spring app (`/actuator/health`); useful while few backend routes exist. */
export function BackendStatus() {
  const { t } = useTranslation();
  const [state, setState] = useState<State>('checking');
  useEffect(() => {
    let live = true;
    fetch('/actuator/health')
      .then((r) => r.json() as Promise<{ status?: string }>)
      .then((b) => live && setState(b.status === 'UP' ? 'up' : 'down'))
      .catch(() => live && setState('down'));
    return () => {
      live = false;
    };
  }, []);
  return <span role="status">{t(`backend.${state}`)}</span>;
}
