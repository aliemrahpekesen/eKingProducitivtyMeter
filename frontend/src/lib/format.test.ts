import { describe, expect, it } from 'vitest';
import { ageDays, hoursOneDecimal, isoDateOnly } from './format';

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

describe('hoursOneDecimal', () => {
  it('formats whole and fractional hours to one decimal', () => {
    expect(hoursOneDecimal(3_600)).toBe('1.0h');
    expect(hoursOneDecimal(5_400)).toBe('1.5h');
  });

  it('rounds to one decimal place', () => {
    expect(hoursOneDecimal(3_660)).toBe('1.0h');
  });

  it('clamps zero and negative to 0.0h', () => {
    expect(hoursOneDecimal(0)).toBe('0.0h');
    expect(hoursOneDecimal(-10)).toBe('0.0h');
  });
});

describe('isoDateOnly', () => {
  it('extracts the date part from a full ISO instant', () => {
    expect(isoDateOnly('2026-01-05T00:00:00Z')).toBe('2026-01-05');
  });

  it('extracts the date part regardless of time-of-day or offset', () => {
    expect(isoDateOnly('2026-01-12T23:59:59.999Z')).toBe('2026-01-12');
    expect(isoDateOnly('2026-01-12T09:00:00+02:00')).toBe('2026-01-12');
  });

  it('is a no-op for an already date-only string', () => {
    expect(isoDateOnly('2026-01-05')).toBe('2026-01-05');
  });

  it('falls back to the raw input when it does not look like an ISO date', () => {
    expect(isoDateOnly('not-a-date')).toBe('not-a-date');
  });
});
