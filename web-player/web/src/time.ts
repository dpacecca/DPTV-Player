export const MIN = 60_000;
export const HALF_HOUR = 30 * MIN;
export const HOUR = 60 * MIN;
export const DAY = 24 * HOUR;

export function clock(ms: number): string {
  return new Date(ms).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }).toLowerCase();
}

export function floorHalfHour(ms: number): number {
  return Math.floor(ms / HALF_HOUR) * HALF_HOUR;
}

/** Local midnight of the day [ms] falls on. */
export function startOfDay(ms: number): number {
  const d = new Date(ms);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

/** "90m", "2h", "1h 30m". */
export function duration(ms: number): string {
  const m = Math.round(ms / MIN);
  if (m < 60) return `${m}m`;
  const h = Math.floor(m / 60);
  return m % 60 ? `${h}h ${m % 60}m` : `${h}h`;
}

/** "Today", "Tomorrow", "Wed 5". */
export function dayLabel(dayStart: number, today: number): string {
  const diff = Math.round((dayStart - today) / DAY);
  if (diff === 0) return 'Today';
  if (diff === 1) return 'Tomorrow';
  if (diff === -1) return 'Yesterday';
  const d = new Date(dayStart);
  return `${d.toLocaleDateString([], { weekday: 'short' })} ${d.getDate()}`;
}

/** "Tue 3 Nov, 4:30 pm". */
export function dateTime(ms: number): string {
  const d = new Date(ms);
  return `${d.toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' })}, ${clock(ms)}`;
}
