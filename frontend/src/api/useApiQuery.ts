import { useCallback, useEffect, useState, type DependencyList } from 'react';

export type QueryState<T> =
  | { status: 'loading'; data?: undefined; error?: undefined }
  | { status: 'success'; data: T; error?: undefined }
  | { status: 'error'; data?: undefined; error: unknown };

const LOADING = { status: 'loading' } as const;

function sameDeps(a: DependencyList | null, b: DependencyList): boolean {
  return a !== null && a.length === b.length && a.every((value, i) => Object.is(value, b[i]));
}

/**
 * 進頁面時載入資料；deps 改變時重新載入，離開頁面或重載時取消前一次請求。
 * 錯誤通常是 ApiError，交給 <ErrorMessage> 顯示。
 */
export function useApiQuery<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  deps: DependencyList,
): QueryState<T> & { reload: () => void } {
  const [attempt, setAttempt] = useState(0);
  const reload = useCallback(() => setAttempt((n) => n + 1), []);
  const requestDeps = [...deps, attempt];

  // 結果記下是哪一組 deps 的；deps 變了（或 reload）就視為載入中，直到新的結果回來
  const [settled, setSettled] = useState<{ deps: DependencyList; result: QueryState<T> } | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    const settle = (result: QueryState<T>) => {
      if (!controller.signal.aborted) setSettled({ deps: requestDeps, result });
    };
    fetcher(controller.signal).then(
      (data) => settle({ status: 'success', data }),
      (error: unknown) => settle({ status: 'error', error }),
    );
    return () => controller.abort();
    // 呼叫端以 deps 列出 fetcher 用到的值，所以 fetcher 本身不列入
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, requestDeps);

  const state = settled && sameDeps(settled.deps, requestDeps) ? settled.result : LOADING;
  return { ...state, reload };
}
