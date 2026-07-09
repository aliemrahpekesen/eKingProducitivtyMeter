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
