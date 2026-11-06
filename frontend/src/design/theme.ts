export type ThemeMode = 'light' | 'dark' | 'system';
export const THEME_STORAGE_KEY = 'e9x.theme';

export function getThemeMode(): ThemeMode {
  try {
    const v = localStorage.getItem(THEME_STORAGE_KEY);
    if (v === 'light' || v === 'dark' || v === 'system') return v;
  } catch {
    /* storage unavailable */
  }
  return 'system';
}

function prefersDark(): boolean {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia('(prefers-color-scheme: dark)').matches;
}

/** Put the resolved theme on <html data-theme>. Mirrors public/theme-init.js, which does it before first paint. */
export function applyTheme(mode: ThemeMode = getThemeMode()): 'light' | 'dark' {
  const resolved = mode === 'system' ? (prefersDark() ? 'dark' : 'light') : mode;
  document.documentElement.setAttribute('data-theme', resolved);
  return resolved;
}

/** Choose a theme for this viewer; remembered in this browser. */
export function setThemeMode(mode: ThemeMode): void {
  try {
    localStorage.setItem(THEME_STORAGE_KEY, mode);
  } catch {
    /* still apply for this session */
  }
  applyTheme(mode);
}
