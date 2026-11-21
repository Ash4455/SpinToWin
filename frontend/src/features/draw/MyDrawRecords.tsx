import type { DrawRecord, DrawRecordPage } from '../../api/records';
import { ErrorMessage } from '../../components/ErrorMessage';
import { formatDateTime } from '../../format';
import { resultLabel } from './DrawResultPanel';

export const RECORDS_PAGE_SIZE = 10;

interface Props {
  /** 最近一次成功載入的那一頁（重新載入時先保留舊資料，畫面不閃） */
  data: DrawRecordPage | undefined;
  error: unknown;
  onRetry: () => void;
  /** 剛抽完、record-service 可能還沒寫入的結果（新到舊），只顯示在第一頁 */
  justDrawn: DrawRecord[];
  /** 轉盤還沒停到的結果不顯示 */
  hiddenDrawIds?: ReadonlySet<string>;
  page: number;
  onPageChange: (page: number) => void;
}

/**
 * 第一頁：剛抽到的結果先放最上面；同一個 requestId 一出現在 API 資料裡就改以 API 為準。
 */
export function mergeJustDrawn(justDrawn: DrawRecord[], fromApi: DrawRecord[]): DrawRecord[] {
  const persisted = new Set(fromApi.map((r) => r.requestId));
  return [...justDrawn.filter((r) => !persisted.has(r.requestId)), ...fromApi];
}

/** 我的抽獎紀錄（分頁，新到舊）。 */
export function MyDrawRecords({ data, error, onRetry, justDrawn, hiddenDrawIds, page, onPageChange }: Props) {
  const fromApi = (data?.content ?? []).filter((r) => !hiddenDrawIds?.has(r.drawId));
  const records = page === 0 ? mergeJustDrawn(justDrawn, fromApi) : fromApi;
  const totalPages = Math.max(data?.totalPages ?? 0, 1);

  return (
    <section className="records" aria-labelledby="records-title">
      <h2 id="records-title">我的抽獎紀錄</h2>
      {error ? (
        <ErrorMessage error={error} onRetry={onRetry} />
      ) : !data ? (
        <p className="muted">載入中…</p>
      ) : records.length === 0 ? (
        <p className="muted">還沒有抽獎紀錄。</p>
      ) : (
        <ol className="records__list" aria-label="抽獎紀錄列表">
          {records.map((r) => (
            <li key={r.drawId} className={`records__item${r.won ? ' is-won' : ''}`}>
              <span className="records__result">{resultLabel(r)}</span>
              <time className="records__time" dateTime={r.drawnAt}>
                {formatDateTime(r.drawnAt)}
              </time>
            </li>
          ))}
        </ol>
      )}
      {data && data.totalPages > 1 && (
        <nav className="pager" aria-label="紀錄分頁">
          <button
            type="button"
            className="button button--secondary"
            disabled={page <= 0}
            onClick={() => onPageChange(page - 1)}
          >
            上一頁
          </button>
          <span>
            第 {page + 1} / {totalPages} 頁
          </span>
          <button
            type="button"
            className="button button--secondary"
            disabled={page + 1 >= totalPages}
            onClick={() => onPageChange(page + 1)}
          >
            下一頁
          </button>
        </nav>
      )}
    </section>
  );
}
