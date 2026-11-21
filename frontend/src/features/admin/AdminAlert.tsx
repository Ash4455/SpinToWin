import { ApiError } from '../../api/client';
import { describeAdminError } from './errors';

interface Props {
  error: unknown;
  /** 已顯示在表單欄位旁的欄位名稱，不再列於橫幅中 */
  shownFields?: readonly string[];
  onRetry?: () => void;
}

/** 後端錯誤的橫幅：錯誤碼的中文說明、後端 detail，以及沒有對應欄位的 errors[]。 */
export function AdminAlert({ error, shownFields = [], onRetry }: Props) {
  if (!error) return null;
  const { title, detail, code } = describeAdminError(error);
  const otherFieldErrors =
    error instanceof ApiError ? (error.problem.errors ?? []).filter((e) => !shownFields.includes(e.field)) : [];

  return (
    <div className="error-message" role="alert">
      <strong className="error-message__title">{title}</strong>
      {(detail || code) && (
        <p className="error-message__detail">
          {detail}
          {code && <code className="admin-alert__code">{code}</code>}
        </p>
      )}
      {otherFieldErrors.length > 0 && (
        <ul className="error-message__fields">
          {otherFieldErrors.map((e) => (
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
