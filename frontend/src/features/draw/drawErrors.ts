import { ApiError } from '../../api/client';
import { ERROR_MESSAGES, errorMessageOf } from '../../api/errorCodes';
import { isUncertainFailure } from './useDrawSession';

export interface DrawErrorText {
  title: string;
  detail?: string;
}

/** 抽獎失敗時給使用者看的訊息：已知錯誤碼用共用的中文說明，其他沿用 Problem Details 的 title／detail。 */
export function drawErrorText(error: unknown): DrawErrorText {
  if (!(error instanceof ApiError)) {
    return { title: '發生未預期的錯誤', detail: error instanceof Error ? error.message : undefined };
  }
  // 429 也可能來自沒有 Problem Details 的中介層
  const known = errorMessageOf(error.code) ?? (error.status === 429 ? ERROR_MESSAGES.RATE_LIMITED : undefined);
  if (known) {
    return { title: known.title, detail: known.hint ?? error.detail };
  }
  if (isUncertainFailure(error)) {
    return {
      title: '無法確認抽獎結果',
      detail: '連線發生問題。按「重新送出」會沿用同一筆請求，不會重複扣抽獎次數。',
    };
  }
  return { title: error.title, detail: error.detail };
}
