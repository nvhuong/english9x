import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import en from './en.json';
import vi from './vi.json';

/**
 * Translations.
 *  - `vi.json` / `en.json` are the `common` namespace (shell + design system strings); keep their key sets identical.
 *  - Later tasks add their own strings WITHOUT touching those files: create `ns/<feature>.vi.json` and `ns/<feature>.en.json`
 *    (both, same keys). They are auto-registered as namespace `<feature>`: `useTranslation('<feature>')`.
 *    i18n.test.ts fails if a pair is missing or the key sets differ.
 */
export const LANGUAGES = ['vi', 'en'] as const;
export type Language = (typeof LANGUAGES)[number];
export const DEFAULT_LANGUAGE: Language = 'vi';
export const LANGUAGE_STORAGE_KEY = 'e9x.lang';

type Dict = Record<string, unknown>;
const nsModules = import.meta.glob<Dict>('./ns/*.{vi,en}.json', { eager: true, import: 'default' });

function resources(): Record<Language, Record<string, Dict>> {
  const res: Record<Language, Record<string, Dict>> = { vi: { common: vi }, en: { common: en } };
  for (const [path, dict] of Object.entries(nsModules)) {
    const m = /\.\/ns\/(.+)\.(vi|en)\.json$/.exec(path);
    if (m) res[m[2] as Language][m[1]!] = dict;
  }
  return res;
}

export function storedLanguage(): Language {
  try {
    const v = localStorage.getItem(LANGUAGE_STORAGE_KEY);
    if (v === 'vi' || v === 'en') return v;
  } catch {
    /* storage unavailable */
  }
  return DEFAULT_LANGUAGE;
}

function applyDocumentLang(lng: Language) {
  if (typeof document !== 'undefined') document.documentElement.lang = lng;
}

/** Change language for this viewer and remember it in this browser. */
export async function setLanguage(lng: Language): Promise<void> {
  try {
    localStorage.setItem(LANGUAGE_STORAGE_KEY, lng);
  } catch {
    /* storage unavailable: still switch for this session */
  }
  applyDocumentLang(lng);
  await i18n.changeLanguage(lng);
}

const res = resources();
const lng = storedLanguage();
void i18n.use(initReactI18next).init({
  resources: {
    vi: res.vi,
    en: res.en,
  },
  lng,
  fallbackLng: DEFAULT_LANGUAGE,
  defaultNS: 'common',
  ns: ['common'],
  interpolation: { escapeValue: false }, // React escapes
  initAsync: false,
  returnNull: false,
});
applyDocumentLang(lng);

/** Test helper: back to a known language without touching storage. */
export async function resetI18n(to: Language = DEFAULT_LANGUAGE): Promise<void> {
  applyDocumentLang(to);
  await i18n.changeLanguage(to);
}

export default i18n;
