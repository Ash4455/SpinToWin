import { useCallback, useState } from 'react';

/**
 * 送出一個寫入操作（建立、修改、上線…）：追蹤進行中與失敗的錯誤。
 * run 回傳是否成功；錯誤保留在 error，交給 AdminAlert 與 fieldErrorsOf 顯示。
 */
export function useAction() {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const run = useCallback(async (action: () => Promise<unknown>): Promise<boolean> => {
    setPending(true);
    setError(null);
    try {
      await action();
      return true;
    } catch (e) {
      setError(e);
      return false;
    } finally {
      setPending(false);
    }
  }, []);

  const reset = useCallback(() => setError(null), []);

  return { pending, error, run, reset };
}
