import { ppmToPercentText, probabilityBreakdown } from './probability';

/** 所有 Prize 的 Win Probability 總和與銘謝惠顧（剩餘機率），以 ppm 精確計算。 */
export function ProbabilitySummary({ prizePpms }: { prizePpms: readonly number[] }) {
  const { prizesPpm, noWinPpm, exceeded } = probabilityBreakdown(prizePpms);
  return (
    <p
      className={`probability-summary${exceeded ? ' probability-summary--exceeded' : ''}`}
      data-testid="probability-summary"
      aria-live="polite"
    >
      <span>Prize 總和 {ppmToPercentText(prizesPpm)}%</span>
      {exceeded ? (
        <span>超過 100%：{ppmToPercentText(-noWinPpm)}%</span>
      ) : (
        <span>銘謝惠顧 {ppmToPercentText(noWinPpm)}%</span>
      )}
    </p>
  );
}
