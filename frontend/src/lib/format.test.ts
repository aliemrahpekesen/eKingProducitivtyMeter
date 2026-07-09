import { describe, expect, it } from 'vitest';
import { ageDays } from './format';

describe('ageDays', () => {
  it('formats whole multi-day ages without a decimal', () => {
    expect(ageDays(5 * 86_400)).toBe('5d');
    expect(ageDays(2 * 86_400)).toBe('2d');
  });

  it('keeps one decimal for fractional days under 10', () => {
    expect(ageDays(Math.round(5.5 * 86_400))).toBe('5.5d');
  });

  it('formats sub-day ages in hours', () => {
    expect(ageDays(5 * 3_600)).toBe('5h');
  });

  it('clamps zero and negative to 0d', () => {
    expect(ageDays(0)).toBe('0d');
    expect(ageDays(-10)).toBe('0d');
  });
});
