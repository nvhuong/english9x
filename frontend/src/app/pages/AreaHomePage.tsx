import { useTranslation } from 'react-i18next';
import { EmptyState } from '../../design';
import type { Area } from '../areas';

const icons: Record<Area, string> = { learner: '📚', cms: '✍', org: '🏫', admin: '🛠', exam: '📝' };

/** Index page of an area while no feature has registered one: an honest empty state, not a blank screen. */
export function AreaHomePage({ area }: { area: Area }) {
  const { t } = useTranslation();
  return <EmptyState headingLevel={1} icon={icons[area]} title={t(`empty.${area}.title`)} description={t(`empty.${area}.description`)} />;
}
