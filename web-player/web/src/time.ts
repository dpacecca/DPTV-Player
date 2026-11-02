export const MIN = 60_000;
export const HALF_HOUR = 30 * MIN;

export function clock(ms: number): string {
  return new Date(ms).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export function floorHalfHour(ms: number): number {
  return Math.floor(ms / HALF_HOUR) * HALF_HOUR;
}
