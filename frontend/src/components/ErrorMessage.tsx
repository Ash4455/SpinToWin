import { ApiError } from '../api/client';

interface Props {
  error: unknown;
  onRetry?: () => void;
}

/** 顯示 API 錯誤：Problem Details 的 title 為標題、detail 為說明，欄位錯誤逐條列出。 */
export function ErrorMessage({ error, onRetry }: Props) {
  const { title, detail, fieldErrors } =
    error instanceof ApiError
      ? { title: error.title, detail: error.detail, fieldErrors: error.problem.errors ?? [] }
      : { title: '發生未預期的錯誤', detail: error instanceof Error ? error.message : undefined, fieldErrors: [] };

  return (
    <div className="error-message" role="alert">
      <strong className="error-message__title">{title}</strong>
      {detail && <p className="error-message__detail">{detail}</p>}
      {fieldErrors.length > 0 && (
        <ul className="error-message__fields">
          {fieldErrors.map((e) => (
            <li key={`${e.field}:${e.message}`}>
              {e.field}：{e.message}
            </li>
          ))}
        </ul>
      )}
      {onRetry && (
        <button type="button" className="button button--secondary" onClick={onRetry}>
          重試
        </button>
      )}
    </div>
  );
}
