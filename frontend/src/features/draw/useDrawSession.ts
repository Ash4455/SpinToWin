import { useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from '../../api/client';
import { postDraws, type DrawResponse, type DrawResult } from '../../api/draws';
import { landingRotation } from '../wheel/wheelLanding';
import type { WheelSlice } from '../wheel/wheelSlices';
import { newIdempotencyKey } from './idempotencyKey';

/** 每格轉動的時間：單抽慢一點，連抽每次快一些 */
export function spinDurationMs(count: number): number {
  return count > 1 ? 1600 : 3000;
}
/** 連抽時停在一格後，顯示該次結果多久才轉下一次 */
export const PAUSE_MS = 600;
/** 網路失敗時自動重送（沿用同一個 Idempotency-Key）前的等待時間；長度即自動重送次數 */
export const RETRY_DELAYS_MS = [500, 1500];

export type DrawPhase =
  | { kind: 'idle' }
  | { kind: 'requesting'; count: number }
  /** 正在播放第 index 次 Draw；landed 表示轉盤已停在該格、結果已揭曉 */
  | { kind: 'playing'; response: DrawResponse; index: number; landed: boolean }
  | { kind: 'done'; response: DrawResponse }
  /** resendable：結果不確定（連線失敗），可沿用同一個 key 重新送出 */
  | { kind: 'failed'; error: unknown; resendable: boolean };

interface Options {
  campaignId: string;
  slices: WheelSlice[];
  /** 拿到抽獎回應（動畫開始前），用來立即更新剩餘次數 */
  onDrawn?: (response: DrawResponse) => void;
  /** 轉盤停在某次 Draw Result 上（結果揭曉） */
  onRevealed?: (result: DrawResult, response: DrawResponse) => void;
  /** 全部播放完畢 */
  onFinished?: (response: DrawResponse) => void;
  onFailed?: (error: unknown) => void;
}

/** 連線失敗或 gateway 暫時無法回應：伺服器可能已經抽完，只能用同一個 key 重送確認。 */
export function isUncertainFailure(error: unknown): boolean {
  return error instanceof ApiError && [0, 502, 503, 504].includes(error.status);
}

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * 一次抽獎的流程：送出請求（網路失敗時以同一個 Idempotency-Key 自動重送）→ 依序播放每個 Draw Result 的轉盤動畫 → 總結。
 * 每次呼叫 draw() 都產生新的 key；resend() 沿用上一次失敗請求的 key 與 count。
 */
export function useDrawSession({ campaignId, slices, onDrawn, onRevealed, onFinished, onFailed }: Options) {
  const [phase, setPhase] = useState<DrawPhase>({ kind: 'idle' });
  const [rotation, setRotation] = useState(0);
  const pending = useRef<{ key: string; count: number } | null>(null);
  const aborter = useRef<AbortController | null>(null);

  const callbacks = useRef({ onDrawn, onRevealed, onFinished, onFailed });
  callbacks.current = { onDrawn, onRevealed, onFinished, onFailed };
  const slicesRef = useRef(slices);
  slicesRef.current = slices;

  useEffect(() => () => aborter.current?.abort(), []);

  const spinTo = useCallback((result: DrawResult | undefined, turns?: number) => {
    if (!result) return;
    // 找不到該格（載入頁面後才新增的 Prize）時轉盤不動，結果仍以文字顯示
    setRotation((from) => landingRotation(slicesRef.current, result.prizeId, from, turns) ?? from);
  }, []);

  const send = useCallback(
    async (key: string, count: number) => {
      aborter.current?.abort();
      const controller = new AbortController();
      aborter.current = controller;
      pending.current = { key, count };
      setPhase({ kind: 'requesting', count });

      let response: DrawResponse;
      try {
        response = await postWithRetry(campaignId, count, key, controller.signal);
      } catch (error) {
        if (controller.signal.aborted) return;
        const resendable = isUncertainFailure(error);
        if (!resendable) pending.current = null;
        setPhase({ kind: 'failed', error, resendable });
        callbacks.current.onFailed?.(error);
        return;
      }
      if (controller.signal.aborted) return;
      pending.current = null;
      callbacks.current.onDrawn?.(response);
      spinTo(response.results[0]);
      setPhase({ kind: 'playing', response, index: 0, landed: false });
    },
    [campaignId, spinTo],
  );

  /** 使用者按下單抽／連抽：新的 Idempotency-Key */
  const draw = useCallback((count: number) => void send(newIdempotencyKey(), count), [send]);

  /** 連線失敗後重新送出：沿用同一個 Idempotency-Key，不會重複扣次 */
  const resend = useCallback(() => {
    if (pending.current) void send(pending.current.key, pending.current.count);
  }, [send]);

  /** 連抽時略過剩下的動畫，直接看總結 */
  const skip = useCallback(() => {
    if (phase.kind !== 'playing') return;
    const { response, index, landed } = phase;
    const unrevealed = response.results.slice(landed ? index + 1 : index);
    unrevealed.forEach((r) => callbacks.current.onRevealed?.(r, response));
    spinTo(response.results.at(-1), 0);
    setPhase({ kind: 'done', response });
    callbacks.current.onFinished?.(response);
  }, [phase, spinTo]);

  useEffect(() => {
    if (phase.kind !== 'playing') return;
    const { response, index, landed } = phase;
    const count = response.results.length;
    const isLast = index === count - 1;

    const timer = setTimeout(
      () => {
        if (!landed) {
          const result = response.results[index];
          if (result) callbacks.current.onRevealed?.(result, response);
          if (isLast) {
            setPhase({ kind: 'done', response });
            callbacks.current.onFinished?.(response);
          } else {
            setPhase({ kind: 'playing', response, index, landed: true });
          }
        } else {
          spinTo(response.results[index + 1]);
          setPhase({ kind: 'playing', response, index: index + 1, landed: false });
        }
      },
      landed ? PAUSE_MS : spinDurationMs(count),
    );
    return () => clearTimeout(timer);
  }, [phase, spinTo]);

  const busy = phase.kind === 'requesting' || phase.kind === 'playing';
  return { phase, rotation, busy, draw, resend, skip };
}

async function postWithRetry(campaignId: string, count: number, key: string, signal: AbortSignal) {
  for (let attempt = 0; ; attempt++) {
    try {
      return await postDraws(campaignId, count, key, signal);
    } catch (error) {
      const delay = RETRY_DELAYS_MS[attempt];
      if (delay === undefined || !isUncertainFailure(error) || signal.aborted) throw error;
      await wait(delay);
      if (signal.aborted) throw error;
    }
  }
}
