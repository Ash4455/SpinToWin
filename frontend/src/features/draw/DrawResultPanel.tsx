import type { DrawResult } from '../../api/draws';
import { NO_WIN_LABEL } from '../wheel/wheelSlices';
import { drawErrorText } from './drawErrors';
import type { DrawPhase } from './useDrawSession';

interface Props {
  phase: DrawPhase;
  onResend: () => void;
  onSkip: () => void;
}

export const resultLabel = (r: Pick<DrawResult, 'won' | 'prizeName'>) => (r.won && r.prizeName ? r.prizeName : NO_WIN_LABEL);

/** 轉盤下方的抽獎狀態：轉動中、單抽結果、連抽逐一揭曉與總結、錯誤。 */
export function DrawResultPanel({ phase, onResend, onSkip }: Props) {
  return (
    <div className="draw-result" role="status" aria-label="抽獎結果" aria-live="polite">
      <PhaseContent phase={phase} onResend={onResend} onSkip={onSkip} />
    </div>
  );
}

function PhaseContent({ phase, onResend, onSkip }: Props) {
  switch (phase.kind) {
    case 'idle':
      return <p className="muted">按下「單抽」或「連抽」開始轉動轉盤。</p>;
    case 'requesting':
      return <p className="muted">抽獎中…</p>;
    case 'failed': {
      const { title, detail } = drawErrorText(phase.error);
      return (
        <div className="error-message" role="alert">
          <strong className="error-message__title">{title}</strong>
          {detail && <p className="error-message__detail">{detail}</p>}
          {phase.resendable && (
            <button type="button" className="button button--secondary" onClick={onResend}>
              重新送出
            </button>
          )}
        </div>
      );
    }
    case 'playing':
    case 'done': {
      const { results } = phase.response;
      const revealedCount =
        phase.kind === 'done' ? results.length : phase.index + (phase.landed ? 1 : 0);
      const revealed = results.slice(0, revealedCount);

      if (results.length === 1) {
        const only = revealed[0];
        if (!only) return <p className="muted">轉盤轉動中…</p>;
        return <SingleResult result={only} />;
      }

      const wins = revealed.filter((r) => r.won).length;
      return (
        <div>
          {phase.kind === 'done' ? (
            <p className="draw-result__summary">
              連抽 {results.length} 次完成：中獎 {wins} 次、{NO_WIN_LABEL} {results.length - wins} 次
            </p>
          ) : (
            <p className="draw-result__progress">
              連抽中：第 {Math.min(phase.index + 1, results.length)} / {results.length} 抽
              <button type="button" className="button button--link" onClick={onSkip}>
                略過動畫
              </button>
            </p>
          )}
          <ol className="draw-result__list" aria-label="連抽結果">
            {revealed.map((r, i) => (
              <li key={r.drawId} className={r.won ? 'is-won' : undefined}>
                第 {i + 1} 抽：{resultLabel(r)}
              </li>
            ))}
          </ol>
        </div>
      );
    }
  }
}

function SingleResult({ result }: { result: DrawResult }) {
  return result.won ? (
    <p className="draw-result__headline is-won">恭喜抽中 {resultLabel(result)}！</p>
  ) : (
    <p className="draw-result__headline">{NO_WIN_LABEL}，下次再接再厲！</p>
  );
}
