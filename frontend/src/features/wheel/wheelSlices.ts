import type { Campaign } from '../../api/campaigns';

export const NO_WIN_LABEL = '銘謝惠顧';

/** 機率很小的格子照比例會窄到看不見，畫面上至少給這麼寬（度）。機率本身不受影響。 */
export const MIN_SLICE_SWEEP = 8;

export interface WheelSlice {
  key: string;
  /** null 代表銘謝惠顧 */
  prizeId: number | null;
  label: string;
  /** 百分比 */
  probability: number;
  /** 角度（度），0 為正上方、順時針遞增 */
  startAngle: number;
  endAngle: number;
}

/**
 * 依 Campaign 的 Prize（依 Prize 順序）與銘謝惠顧（最後一格）切出轉盤各格。
 * 各格寬度與機率成正比，但至少 MIN_SLICE_SWEEP 度；不足的部分由其他格依比例讓出。
 */
export function buildWheelSlices(campaign: Campaign): WheelSlice[] {
  const outcomes = [
    ...campaign.prizes.map((p) => ({ key: `prize-${p.id}`, prizeId: p.id, label: p.name, probability: p.winProbability })),
    { key: 'no-win', prizeId: null, label: NO_WIN_LABEL, probability: campaign.noWinProbability },
  ];
  const sweeps = sweepsWithMinimum(
    outcomes.map((o) => o.probability),
    MIN_SLICE_SWEEP,
  );

  let angle = 0;
  return outcomes.map((o, i) => {
    const startAngle = angle;
    angle += sweeps[i] ?? 0;
    return { ...o, startAngle, endAngle: angle };
  });
}

/** 把 360° 依權重分配，每份至少 min；放不下時平均分配。 */
function sweepsWithMinimum(weights: number[], min: number): number[] {
  const n = weights.length;
  if (n * min >= 360) return weights.map(() => 360 / n);

  const pinned = new Set<number>();
  for (;;) {
    const free = weights.map((_, i) => i).filter((i) => !pinned.has(i));
    const freeWeight = free.reduce((sum, i) => sum + (weights[i] ?? 0), 0);
    const freeSweep = 360 - pinned.size * min;
    const share = (i: number) =>
      freeWeight > 0 ? ((weights[i] ?? 0) / freeWeight) * freeSweep : freeSweep / free.length;

    const tooSmall = free.filter((i) => share(i) < min);
    if (tooSmall.length === 0) {
      return weights.map((_, i) => (pinned.has(i) ? min : share(i)));
    }
    tooSmall.forEach((i) => pinned.add(i));
  }
}
