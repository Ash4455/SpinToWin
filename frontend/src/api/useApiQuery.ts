import { useCallback, useEffect, useState, type DependencyList } from 'react';

export type QueryState<T> =
  | { status: 'loading'; data?: undefined; error?: undefined }
  | { status: 'success'; data: T; error?: undefined }
  | { status: 'error'; data?: undefined; error: unknown };

/**
 * 進頁面時載入資料；deps 改變時重新載入，離開頁面或重載時取消前一次請求。
 * 錯誤通常是 ApiError，交給 <ErrorMessage> 顯示。
 */
export function useApiQuery<T>(
  fetcher: (signal: AbortSignal) => Promise<T>,
  deps: DependencyList,
): QueryState<T> & { reload: () => void } {
  const [state, setState] = useState<QueryState<T>>({ status: 'loading' });
  const [attempt, setAttempt] = useState(0);
  const reload = useCallback(() => setAttempt((n) => n + 1), []);

  useEffect(() => {
    const controller = new AbortController();
    setState({ status: 'loading' });
    fetcher(controller.signal).then(
      (data) => !controller.signal.aborted && setState({ status: 'success', data }),
      (error: unknown) => !controller.signal.aborted && setState({ status: 'error', error }),
    );
    return () => controller.abort();
  }, [...deps, attempt]);

  return { ...state, reload };
}
