import type { WheelSlice } from './wheelSlices';

export interface LabelGeometry {
  center: number;
  /** 文字外端離圓心的距離 */
  outerRadius: number;
  /** 文字內端最多只能到這裡（避開中心圓） */
  innerRadius: number;
  fontSize: number;
}

export interface LabelLayout {
  text: string;
  /** 文字中點 */
  x: number;
  y: number;
  /** SVG rotate 角度（度） */
  rotate: number;
}

const ELLIPSIS = '…';

/** 估計文字寬度：全形字約 1em，半形字約 0.6em。 */
export function estimateTextWidth(text: string, fontSize: number): number {
  let width = 0;
  for (const ch of text) {
    width += (ch.codePointAt(0) ?? 0) > 0xff ? fontSize : fontSize * 0.6;
  }
  return width;
}

/** 文字沿半徑排、外端貼著外緣時，放不放得進這一格 */
function fits(text: string, sweepDeg: number, g: LabelGeometry): boolean {
  const innerEnd = g.outerRadius - estimateTextWidth(text, g.fontSize);
  if (innerEnd < g.innerRadius) return false;
  const halfSweep = ((sweepDeg / 2) * Math.PI) / 180;
  return 2 * innerEnd * Math.sin(halfSweep) >= g.fontSize;
}

/**
 * 轉盤格子上的名稱：沿半徑方向排（窄格也放得下），放不下就截短加「…」，
 * 連一個字都放不下就回傳 null 不畫（圖例仍會列出完整名稱與機率）。
 */
export function sliceLabelLayout(slice: WheelSlice, g: LabelGeometry): LabelLayout | null {
  const sweep = slice.endAngle - slice.startAngle;
  const chars = [...slice.label];

  let text = slice.label;
  for (let keep = chars.length - 1; !fits(text, sweep, g); keep--) {
    if (keep < 1) return null;
    text = chars.slice(0, keep).join('').trimEnd() + ELLIPSIS;
  }

  const mid = (slice.startAngle + slice.endAngle) / 2;
  const r = g.outerRadius - estimateTextWidth(text, g.fontSize) / 2;
  const rad = ((mid - 90) * Math.PI) / 180;
  // 角度 0 在正上方、順時針；右半邊文字由內往外讀，左半邊轉 180° 避免上下顛倒
  const rotate = mid <= 180 ? mid - 90 : mid + 90;
  return { text, x: g.center + r * Math.cos(rad), y: g.center + r * Math.sin(rad), rotate };
}
