import { ApiError } from '../../api/client';
import { type ErrorCode, errorMessageOf } from '../../api/errorCodes';

export interface ErrorDescription {
  title: string;
  detail?: string;
  code?: string;
}

/** 錯誤橫幅要顯示的內容：已知錯誤碼用共用的中文說明（api/errorCodes），並保留後端的 detail。 */
export function describeAdminError(error: unknown): ErrorDescription {
  if (error instanceof ApiError) {
    return { title: errorMessageOf(error.code)?.title ?? error.title, detail: error.detail, code: error.code };
  }
  return { title: '發生未預期的錯誤', detail: error instanceof Error ? error.message : undefined, code: undefined };
}

/** 表單欄位名稱 → 錯誤訊息。 */
export type FieldErrors = Partial<Record<string, string>>;

/**
 * 把後端錯誤對應到表單欄位：
 * - Problem Details 的 errors[]（VALIDATION_FAILED）依 field 對應，同一欄位多筆以「；」串接
 * - 與某欄位相關的業務錯誤碼（例如 PROBABILITY_SUM_EXCEEDED → winProbability）依 codeFields 對應
 */
export function fieldErrorsOf(error: unknown, codeFields: Partial<Record<ErrorCode, string>> = {}): FieldErrors {
  if (!(error instanceof ApiError)) return {};
  const result: FieldErrors = {};
  for (const { field, message } of error.problem.errors ?? []) {
    result[field] = result[field] ? `${result[field]}；${message}` : message;
  }
  const codeField = error.code ? codeFields[error.code as ErrorCode] : undefined;
  if (codeField) {
    result[codeField] ??= errorMessageOf(error.code)?.title ?? error.title;
  }
  return result;
}
