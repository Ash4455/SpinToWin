import type { WheelSlice } from './wheelSlices';

/** 每次 Draw 至少多轉幾圈，看起來才像在轉 */
export const SPIN_TURNS = 5;

const mod360 = (deg: number) => ((deg % 360) + 360) % 360;

/**
 * 轉盤從 fromRotation 順時針轉到「prizeId 那格的中點對準正上方指針」所需的旋轉角度（度）。
 * prizeId 為 null 代表銘謝惠顧。找不到該格（例如載入頁面後才新增的 Prize）時回傳 undefined。
 *
 * 轉盤順時針轉 r 度後，指針（角度 0）對到的是轉盤上原本 −r 的位置，
 * 所以要停在中點 m，r 必須 ≡ −m (mod 360)。
 */
export function landingRotation(
  slices: WheelSlice[],
  prizeId: number | null,
  fromRotation: number,
  turns = SPIN_TURNS,
): number | undefined {
  const slice = slices.find((s) => s.prizeId === prizeId);
  if (!slice) return undefined;
  const mid = (slice.startAngle + slice.endAngle) / 2;
  const forward = mod360(-mid - fromRotation);
  return fromRotation + turns * 360 + forward;
}

/** 轉盤轉了 rotation 度後，正上方指針指到的格子。 */
export function sliceAtPointer(slices: WheelSlice[], rotation: number): WheelSlice | undefined {
  const angle = mod360(-rotation);
  return slices.find((s) => angle >= s.startAngle && angle < s.endAngle);
}
