/** `<input type="datetime-local">` 使用瀏覽器所在時區；API 使用 ISO-8601 UTC。 */

const pad = (n: number) => String(n).padStart(2, '0');

/** ISO（UTC）→ datetime-local 的值（本地時間，到分鐘） */
export function isoToLocalInput(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** datetime-local 的值 → ISO（UTC）；未填或無效回傳 null，讓後端回欄位錯誤。 */
export function localInputToIso(value: string): string | null {
  if (!value) return null;
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? null : d.toISOString();
}
