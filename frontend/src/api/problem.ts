import type { ErrorCode } from './errorCodes';

/** 後端統一的 RFC 9457 Problem Details（見 common 模組的 Problems）。 */
export interface ProblemDetail {
  type?: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  /** 機器可讀的錯誤碼，例如 CAMPAIGN_NOT_FOUND、QUOTA_EXCEEDED（框架錯誤可能是 ErrorCode 以外的值，如 NOT_FOUND） */
  code?: ErrorCode | (string & {});
  /** 欄位驗證錯誤（VALIDATION_FAILED） */
  errors?: { field: string; message: string }[];
}

/** 所有 API 失敗都以 ApiError 拋出；畫面以 title／detail 顯示，邏輯以 code 判斷。 */
export class ApiError extends Error {
  readonly status: number;
  readonly title: string;
  readonly detail?: string;
  readonly code?: ErrorCode | (string & {});
  readonly problem: ProblemDetail;

  constructor(problem: ProblemDetail) {
    super(problem.detail ? `${problem.title}: ${problem.detail}` : problem.title);
    this.name = 'ApiError';
    this.problem = problem;
    this.status = problem.status;
    this.title = problem.title;
    this.detail = problem.detail;
    this.code = problem.code;
  }
}

function isProblemDetail(body: unknown): body is ProblemDetail {
  return typeof body === 'object' && body !== null && typeof (body as ProblemDetail).title === 'string';
}

/** 把失敗的 HTTP 回應轉成 ApiError。 */
export async function apiErrorFrom(response: Response): Promise<ApiError> {
  const body = parseJson(await response.text());
  if (isProblemDetail(body)) {
    return new ApiError({ ...body, status: body.status ?? response.status });
  }
  return new ApiError({ title: response.statusText || `HTTP ${response.status}`, status: response.status });
}

/** fetch 本身失敗（離線、DNS、CORS…）時的錯誤。 */
export function networkError(cause: unknown): ApiError {
  const error = new ApiError({ title: '無法連線到伺服器', status: 0, detail: '請檢查網路連線後再試一次。' });
  error.cause = cause;
  return error;
}

function parseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}
