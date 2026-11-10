import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiProblemError } from '../../api/problem';
import { ForbiddenPage } from '../../app/pages/ForbiddenPage';
import { Button, Input } from '../../design';
import { FormAlert, FormNotice } from '../auth/components';
import { usePaymentAccount, useSavePaymentAccount } from '../billing/queries';

const BIN = /^[0-9]{6}$/;
const ACCOUNT = /^[A-Za-z0-9]{1,19}$/;

/** /admin/payment-account: the one bank account that receives payments (SYS_ADMIN). Saving retires the previous account. */
export default function PaymentAccountPage() {
  const { t } = useTranslation('billing');
  const current = usePaymentAccount();
  const save = useSavePaymentAccount();
  const [bankBin, setBankBin] = useState('');
  const [accountNo, setAccountNo] = useState('');
  const [accountName, setAccountName] = useState('');
  const [saved, setSaved] = useState(false);
  const [failed, setFailed] = useState(false);
  const [touched, setTouched] = useState(false);

  if (current.isError && current.error instanceof ApiProblemError && current.error.status === 403) return <ForbiddenPage />;

  const binError = touched && !BIN.test(bankBin) ? t('admin.account.binInvalid') : undefined;
  const noError = touched && !ACCOUNT.test(accountNo) ? t('admin.account.noInvalid') : undefined;
  const nameError = touched && (accountName.trim() === '' || accountName.length > 100) ? t('admin.account.nameInvalid') : undefined;

  return (
    <div className="flex flex-col gap-6">
      <header>
        <h1 className="font-serif text-3xl font-bold text-ink">{t('admin.account.title')}</h1>
        <p className="mt-2 max-w-prose text-muted">{t('admin.account.description')}</p>
      </header>

      <section aria-labelledby="current-account" className="flex flex-col gap-1">
        <h2 id="current-account" className="font-serif text-xl font-semibold">
          {t('admin.account.current')}
        </h2>
        {current.isPending && <p role="status" className="text-muted">{t('admin.account.loading')}</p>}
        {current.isError && !(current.error instanceof ApiProblemError && current.error.status === 404) && <FormAlert>{t('admin.account.loadFailed')}</FormAlert>}
        {current.isError && current.error instanceof ApiProblemError && current.error.status === 404 && <p className="text-muted">{t('admin.account.none')}</p>}
        {current.data && (
          <p data-testid="current-account">
            {t('admin.account.summary', { bin: current.data.bankBin, no: current.data.accountNo, name: current.data.accountName })}
          </p>
        )}
      </section>

      <form
        noValidate
        aria-label={t('admin.account.formTitle')}
        className="flex max-w-xl flex-col gap-4"
        onSubmit={(e) => {
          e.preventDefault();
          setTouched(true);
          setSaved(false);
          setFailed(false);
          if (!BIN.test(bankBin) || !ACCOUNT.test(accountNo) || accountName.trim() === '' || accountName.length > 100) return;
          save.mutate(
            { bankBin, accountNo, accountName: accountName.trim() },
            {
              onSuccess: () => {
                setSaved(true);
                setBankBin('');
                setAccountNo('');
                setAccountName('');
                setTouched(false);
              },
              onError: () => setFailed(true),
            },
          );
        }}
      >
        <h2 className="font-serif text-xl font-semibold">{t('admin.account.formTitle')}</h2>
        {failed && <FormAlert>{t('admin.account.saveFailed')}</FormAlert>}
        {saved && <FormNotice>{t('admin.account.saved')}</FormNotice>}
        <Input label={t('admin.account.bin')} hint={t('admin.account.binHint')} inputMode="numeric" maxLength={6} value={bankBin} onChange={(e) => setBankBin(e.target.value)} error={binError} />
        <Input label={t('admin.account.no')} maxLength={19} value={accountNo} onChange={(e) => setAccountNo(e.target.value)} error={noError} />
        <Input label={t('admin.account.name')} maxLength={100} value={accountName} onChange={(e) => setAccountName(e.target.value)} error={nameError} />
        <div>
          <Button type="submit" loading={save.isPending}>
            {t('admin.account.save')}
          </Button>
        </div>
      </form>
    </div>
  );
}
