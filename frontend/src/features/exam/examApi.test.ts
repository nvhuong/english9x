import { describe, expect, it } from 'vitest';
import { parseDuration } from './examApi';

describe('parseDuration', () => {
  it('reads the server duration texts and does not guess at bare numbers', () => {
    expect(parseDuration('5s')).toBe(5000);
    expect(parseDuration('500ms')).toBe(500);
    expect(parseDuration('1m')).toBe(60000);
    expect(parseDuration(500)).toBeNull();
    expect(parseDuration('soon')).toBeNull();
    expect(parseDuration('0s')).toBeNull();
  });
});
