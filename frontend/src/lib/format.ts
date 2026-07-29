/** Formats an age in seconds as a compact human string (e.g. "5d", "5.5d", "12h"). */
export function ageDays(seconds: number): string {
  if (seconds <= 0) {
    return '0d';
  }
  const days = seconds / 86_400;
  if (days >= 1) {
    const rounded = days < 10 ? Math.round(days * 10) / 10 : Math.round(days);
    return `${rounded}d`;
  }
  const hours = Math.max(1, Math.round(seconds / 3_600));
  return `${hours}h`;
}

/** Formats a duration in seconds as hours to one decimal place (e.g. "12.3h"). Negative durations
 *  clamp to "0.0h" — durations are never negative in the domain, so this is a display safeguard. */
export function hoursOneDecimal(seconds: number): string {
  const safe = Math.max(0, seconds);
  return `${(safe / 3_600).toFixed(1)}h`;
}

/** Extracts the `YYYY-MM-DD` date part from an ISO-8601 instant (e.g. "2026-01-05T00:00:00Z" →
 *  "2026-01-05"). Slices the string rather than going through `Date`/timezone math, so the result
 *  is deterministic regardless of the viewer's locale or timezone. Falls back to the raw input if
 *  it doesn't look like an ISO date (defensive — the contract guarantees ISO instants). */
export function isoDateOnly(iso: string): string {
  const match = /^(\d{4}-\d{2}-\d{2})/.exec(iso);
  return match !== null ? match[1] : iso;
}
