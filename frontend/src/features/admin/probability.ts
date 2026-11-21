/**
 * Win Probability 的換算與加總，與後端相同以 ppm（百萬分之一）整數計算，避免浮點誤差。
 * 1% = 10,000 ppm；100% = 1,000,000 ppm；最小單位 0.0001% = 1 ppm。
 */
export const ONE_HUNDRED_PERCENT_PPM = 1_000_000;
const PPM_PER_PERCENT = 10_000;

const PERCENT_PATTERN = /^(\d{1,3})(?:\.(\d{1,4}))?$/;

/** 使用者輸入的百分比字串（最多 4 位小數、0～100）→ ppm；格式不符回傳 null。 */
export function parsePercentToPpm(input: string): number | null {
  const match = PERCENT_PATTERN.exec(input.trim());
  if (!match) return null;
  const [, whole = '0', fraction = ''] = match;
  const ppm = Number(whole) * PPM_PER_PERCENT + Number(fraction.padEnd(4, '0'));
  return ppm <= ONE_HUNDRED_PERCENT_PPM ? ppm : null;
}

/** ppm → 百分比文字（去掉多餘的 0），例如 95000 → "9.5"、1 → "0.0001"。 */
export function ppmToPercentText(ppm: number): string {
  const sign = ppm < 0 ? '-' : '';
  const abs = Math.abs(ppm);
  const whole = Math.floor(abs / PPM_PER_PERCENT);
  const fraction = String(abs % PPM_PER_PERCENT).padStart(4, '0').replace(/0+$/, '');
  return `${sign}${whole}${fraction ? `.${fraction}` : ''}`;
}

/** API 回傳的百分比數值（後端為最多 4 位小數的 BigDecimal）→ ppm。 */
export function percentToPpm(percent: number): number {
  return Math.round(percent * PPM_PER_PERCENT);
}

export interface ProbabilityBreakdown {
  /** 所有 Prize 的 Win Probability 總和 */
  prizesPpm: number;
  /** 銘謝惠顧 = 100% − 總和；超過 100% 時為負數 */
  noWinPpm: number;
  /** 總和超過 100%，後端會以 PROBABILITY_SUM_EXCEEDED 拒絕 */
  exceeded: boolean;
}

export function probabilityBreakdown(prizePpms: readonly number[]): ProbabilityBreakdown {
  const prizesPpm = prizePpms.reduce((sum, ppm) => sum + ppm, 0);
  return {
    prizesPpm,
    noWinPpm: ONE_HUNDRED_PERCENT_PPM - prizesPpm,
    exceeded: prizesPpm > ONE_HUNDRED_PERCENT_PPM,
  };
}

/** ppm → 送給 API 的百分比數值；JSON.stringify 會輸出最短且精確的字面值（例如 333333 → 33.3333）。 */
export function ppmToPercent(ppm: number): number {
  return ppm / PPM_PER_PERCENT;
}

export interface ProbabilityInputCheck {
  /** 輸入有效時的 ppm */
  ppm: number | null;
  /** 這個輸入套用後所有 Prize 的 ppm（無效輸入以 0 計），給即時總和顯示 */
  prizePpms: number[];
  /** 不可送出的原因；空白輸入沒有訊息但仍不可送出 */
  error?: string;
  canSubmit: boolean;
}

/** 檢查一個 Win Probability 輸入：格式（0～100、最多 4 位小數），以及加上其他 Prize 後是否超過 100%。 */
export function checkProbabilityInput(input: string, otherPrizePpms: readonly number[]): ProbabilityInputCheck {
  const ppm = parsePercentToPpm(input);
  const prizePpms = [...otherPrizePpms, ppm ?? 0];
  if (input.trim() === '') return { ppm: null, prizePpms, canSubmit: false };
  if (ppm === null) {
    return { ppm, prizePpms, canSubmit: false, error: '請輸入 0～100 的百分比，最多 4 位小數' };
  }
  const { exceeded, prizesPpm } = probabilityBreakdown(prizePpms);
  if (exceeded) {
    return {
      ppm,
      prizePpms,
      canSubmit: false,
      error: `所有 Prize 的總和超過 100%（${ppmToPercentText(prizesPpm)}%），請調低 Win Probability`,
    };
  }
  return { ppm, prizePpms, canSubmit: true };
}
