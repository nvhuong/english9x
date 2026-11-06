import { act, render, screen } from '@testing-library/react';
import { useTranslation } from 'react-i18next';
import { describe, expect, it } from 'vitest';
import i18n, { LANGUAGE_STORAGE_KEY, setLanguage, storedLanguage } from './index';

function Probe() {
  const { t } = useTranslation();
  return <p>{t('common.close')}</p>;
}

describe('language switching', () => {
  it('defaults to Vietnamese', () => {
    render(<Probe />);
    expect(screen.getByText('Đóng')).toBeInTheDocument();
    expect(i18n.language).toBe('vi');
  });

  it('switches to English, persists the choice and updates <html lang>', async () => {
    render(<Probe />);
    await act(() => setLanguage('en'));
    expect(screen.getByText('Close')).toBeInTheDocument();
    expect(localStorage.getItem(LANGUAGE_STORAGE_KEY)).toBe('en');
    expect(storedLanguage()).toBe('en');
    expect(document.documentElement.lang).toBe('en');
  });

  it('ignores an unknown stored value', () => {
    localStorage.setItem(LANGUAGE_STORAGE_KEY, 'fr');
    expect(storedLanguage()).toBe('vi');
  });
});
