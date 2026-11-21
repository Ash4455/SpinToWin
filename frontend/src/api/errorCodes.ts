/** 後端 Problem Details 的錯誤碼（與 backend common 模組的 ErrorCode 一致）。 */
export type ErrorCode =
  | 'VALIDATION_FAILED'
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'CAMPAIGN_NOT_FOUND'
  | 'INVALID_STATUS_TRANSITION'
  | 'CAMPAIGN_NOT_EDITABLE'
  | 'PRIZE_NOT_FOUND'
  | 'PROBABILITY_SUM_EXCEEDED'
  | 'STOCK_NEGATIVE'
  | 'CAMPAIGN_HAS_NO_PRIZE'
  | 'CAMPAIGN_NOT_ACTIVE'
  | 'QUOTA_EXCEEDED'
  | 'IDEMPOTENCY_KEY_REQUIRED'
  | 'RATE_LIMITED'
  | 'INTERNAL_ERROR';

/**
 * 錯誤碼的說明。
 * - title：取代 Problem Details 的英文 title
 * - hint：給使用者的下一步建議；抽獎畫面以它取代後端的 detail（後台照樣顯示後端的 detail）
 */
export interface ErrorMessage {
  title: string;
  hint?: string;
}

/** 全站共用的錯誤碼說明；未列出的錯誤碼沿用 Problem Details 的 title／detail。 */
export const ERROR_MESSAGES: Partial<Record<ErrorCode, ErrorMessage>> = {
  VALIDATION_FAILED: { title: '輸入資料有誤，請修正標示的欄位' },
  UNAUTHORIZED: { title: '登入已失效，請重新登入' },
  FORBIDDEN: { title: '需要 ADMIN 角色' },
  CAMPAIGN_NOT_FOUND: { title: '找不到這個 Campaign' },
  PRIZE_NOT_FOUND: { title: '找不到這個 Prize' },
  CAMPAIGN_NOT_EDITABLE: { title: '目前的 Campaign Status 不允許這項修改' },
  INVALID_STATUS_TRANSITION: { title: '目前的 Campaign Status 不能進行這個轉換' },
  CAMPAIGN_HAS_NO_PRIZE: { title: 'Campaign 至少需要一個 Prize 才能上線' },
  PROBABILITY_SUM_EXCEEDED: { title: '所有 Prize 的 Win Probability 總和不可超過 100%' },
  STOCK_NEGATIVE: { title: 'Stock 不可變成負數' },
  CAMPAIGN_NOT_ACTIVE: { title: '活動目前未開放抽獎', hint: '活動可能尚未開始、已經結束或尚未上線。' },
  QUOTA_EXCEEDED: { title: '抽獎次數不足', hint: '剩餘次數不足以進行這次抽獎，請減少連抽次數。' },
  RATE_LIMITED: { title: '操作太頻繁', hint: '請稍等幾秒後再抽。' },
};

/** 錯誤碼的說明；未知或沒有說明的錯誤碼回傳 undefined。 */
export function errorMessageOf(code: string | undefined): ErrorMessage | undefined {
  return code !== undefined && Object.hasOwn(ERROR_MESSAGES, code) ? ERROR_MESSAGES[code as ErrorCode] : undefined;
}
