// @vitest-environment node
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { contrastRatio } from './contrast';

const here = dirname(fileURLToPath(import.meta.url));
const css = readFileSync(join(here, 'tokens.css'), 'utf8');

/** Colour declarations of the first block whose selector line starts with `selector`. */
function block(selector: string): Record<string, string> {
  const start = css.indexOf(`${selector} {`);
  if (start < 0) throw new Error(`no block ${selector}`);
  const body = css.slice(css.indexOf('{', start) + 1, css.indexOf('\n}', start));
  const out: Record<string, string> = {};
  for (const m of body.matchAll(/--color-([a-z-]+):\s*(#[0-9a-fA-F]{6})\s*;/g)) out[m[1]!] = m[2]!;
  return out;
}

const light = block('@theme static');
const dark = { ...light, ...block(":root[data-theme='dark']") };
const examLight = { ...light, ...block("[data-surface='exam']") };
const examDark = { ...dark, ...block(":root[data-theme='dark'] [data-surface='exam']") };

type Pair = [fg: string, bg: string, min: number, why: string];
// Text pairs need 4.5:1 (AA normal text); UI boundaries/focus need 3:1 (AA non-text).
const pairs: Pair[] = [
  ['ink', 'paper', 4.5, 'body text on page'],
  ['ink', 'surface', 4.5, 'body text on card'],
  ['ink', 'sunken', 4.5, 'body text on sunken'],
  ['muted', 'paper', 4.5, 'secondary text on page'],
  ['muted', 'surface', 4.5, 'secondary text on card'],
  ['muted', 'sunken', 4.5, 'secondary text on sunken'],
  ['primary', 'paper', 4.5, 'links / primary text on page'],
  ['primary', 'surface', 4.5, 'links / primary text on card'],
  ['on-primary', 'primary', 4.5, 'primary button'],
  ['on-primary', 'primary-hover', 4.5, 'primary button hover'],
  ['on-accent', 'accent', 4.5, 'accent button'],
  ['on-accent', 'accent-hover', 4.5, 'accent button hover'],
  ['accent', 'paper', 3, 'accent used as large/graphic'],
  ['edge', 'surface', 3, 'input boundary'],
  ['edge', 'paper', 3, 'control boundary on page'],
  ['focus', 'paper', 3, 'focus ring on page'],
  ['focus', 'surface', 3, 'focus ring on card'],
  ['success', 'success-bg', 4.5, 'success badge'],
  ['warning', 'warning-bg', 4.5, 'warning badge'],
  ['danger', 'danger-bg', 4.5, 'danger badge'],
  ['info', 'info-bg', 4.5, 'info badge'],
  ['danger', 'surface', 4.5, 'error text on card'],
  ['danger', 'paper', 4.5, 'error text on page'],
  ['tip', 'tip-bg', 4.5, 'tip box'],
  ['trap', 'trap-bg', 4.5, 'trap box'],
  ['mark', 'mark-bg', 4.5, 'reading highlight'],
  ['clock-warn', 'clock-warn-bg', 4.5, 'clock 5 minutes'],
  ['clock-danger', 'clock-danger-bg', 4.5, 'clock 1 minute'],
];

const themes: Array<[string, Record<string, string>, Pair[]]> = [
  ['light', light, pairs],
  ['dark', dark, pairs],
  // The exam surface re-skins neutrals only; the pairs that exist there must stay AA too.
  ['exam light', examLight, pairs.filter(([f, b]) => ['ink', 'muted', 'primary', 'on-primary', 'edge'].includes(f) && b !== 'sunken' || b === 'paper')],
  ['exam dark', examDark, pairs.filter(([f, b]) => ['ink', 'muted', 'primary', 'on-primary', 'edge'].includes(f) && b !== 'sunken' || b === 'paper')],
];

describe('design token contrast (WCAG 2.1 AA)', () => {
  for (const [name, theme, list] of themes) {
    describe(name, () => {
      for (const [fg, bg, min, why] of list) {
        it(`${fg} on ${bg} >= ${min}:1 (${why})`, () => {
          const f = theme[fg];
          const b = theme[bg];
          expect(f, `token ${fg}`).toBeDefined();
          expect(b, `token ${bg}`).toBeDefined();
          const ratio = contrastRatio(f!, b!);
          expect(ratio, `${fg} ${f} on ${bg} ${b} = ${ratio.toFixed(2)}`).toBeGreaterThanOrEqual(min);
        });
      }
    });
  }

  it('dark theme redefines every colour token that light defines', () => {
    const own = block(":root[data-theme='dark']");
    const missing = Object.keys(light).filter((k) => !(k in own));
    expect(missing).toEqual([]);
  });
});

describe('contrast helper', () => {
  it('matches the WCAG reference values', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 5);
    expect(contrastRatio('#777777', '#ffffff')).toBeCloseTo(4.48, 1);
  });
});

describe('token discipline', () => {
  it('has a prefers-reduced-motion treatment that removes animation and transitions', () => {
    const m = /@media \(prefers-reduced-motion: reduce\)[^]*?\n\}/.exec(css);
    expect(m?.[0]).toMatch(/animation-duration:\s*0\.01ms/);
    expect(m?.[0]).toMatch(/transition-duration:\s*0\.01ms/);
  });

  it('declares font-display: swap and ships a Vietnamese subset for both families', () => {
    const fonts = readFileSync(join(here, 'fonts.css'), 'utf8');
    expect(fonts).not.toMatch(/fonts\.googleapis|fonts\.gstatic/);
    const faces = fonts.match(/@font-face[^}]+}/g) ?? [];
    expect(faces.length).toBeGreaterThan(0);
    for (const f of faces) expect(f).toContain('font-display: swap');
    for (const family of ['Be Vietnam Pro', 'Lora']) {
      expect(faces.some((f) => f.includes(`'${family}'`) && f.includes('vietnamese') && f.includes('U+1EA0-1EF9'))).toBe(true);
    }
  });

  it('source code uses no raw colours or Tailwind palette colours', () => {
    const root = resolve(here, '..');
    const offenders: string[] = [];
    const walk = (dir: string) => {
      for (const e of readdirSync(dir)) {
        const p = join(dir, e);
        if (statSync(p).isDirectory()) {
          if (e !== 'generated') walk(p);
        } else if (/\.(tsx?|css)$/.test(e) && !/tokens\.(css|test\.ts)$|contrast\.test/.test(e) && !/\.test\.tsx?$/.test(e)) {
          const text = readFileSync(p, 'utf8');
          if (/#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3}\b(?![\w-])/.test(text.replace(/&#\d+;/g, ''))) offenders.push(`${p}: raw hex`);
          if (/\b(?:bg|text|border|ring|fill|stroke|from|to|via)-(?:red|blue|green|gray|slate|zinc|neutral|stone|yellow|orange|amber|white|black)(?:-\d+)?\b/.test(text)) offenders.push(`${p}: tailwind palette`);
        }
      }
    };
    walk(root);
    expect(offenders).toEqual([]);
  });
});
