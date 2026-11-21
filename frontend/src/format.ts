const dateTimeFormat = new Intl.DateTimeFormat('zh-TW', { dateStyle: 'medium', timeStyle: 'short' });
const percentFormat = new Intl.NumberFormat('zh-TW', { maximumFractionDigits: 4 });

/** ISO 時間 → 使用者所在時區的日期時間 */
export function formatDateTime(iso: string): string {
  return dateTimeFormat.format(new Date(iso));
}

/** 百分比數值（例如 9.5）→ "9.5%" */
export function formatPercent(percent: number): string {
  return `${percentFormat.format(percent)}%`;
}
