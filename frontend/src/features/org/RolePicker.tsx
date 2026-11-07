import { useTranslation } from 'react-i18next';
import { CheckboxField } from '../auth/components';
import { roleChoices, roleLabel, type Scope } from './roles';

export interface RolePickerProps {
  scope: Scope;
  /** Selected choice keys (see roles.ts). */
  value: readonly string[];
  onChange: (keys: string[]) => void;
  error?: string;
}

/** The roles of a scope as labelled checkboxes in one fieldset. */
export function RolePicker({ scope, value, onChange, error }: RolePickerProps) {
  const { t } = useTranslation('org');
  return (
    <fieldset className="flex flex-col gap-2 rounded-md border border-line p-3">
      <legend className="px-1 text-sm font-semibold text-ink">{t('invite.legend')}</legend>
      <div className="grid gap-2 sm:grid-cols-2">
        {roleChoices(scope).map((c) => (
          <CheckboxField
            key={c.key}
            label={roleLabel(t, c.role, c.examCode)}
            checked={value.includes(c.key)}
            onChange={(e) => onChange(e.target.checked ? [...value, c.key] : value.filter((k) => k !== c.key))}
          />
        ))}
      </div>
      {error && (
        <p role="alert" className="text-sm font-medium text-danger">
          {error}
        </p>
      )}
    </fieldset>
  );
}
