import { describe, expect, it } from 'vitest';
import type { Campaign } from '../../api/campaigns';
import { landingRotation, sliceAtPointer } from './wheelLanding';
import { buildWheelSlices } from './wheelSlices';

/** 三個 Prize 各 25%、銘謝惠顧 25%：每格 90°，中點依序為 45°、135°、225°、315° */
const quarters: Campaign = {
  id: 1,
  name: '四等分',
  startAt: '2026-01-01T00:00:00Z',
  endAt: '2030-12-31T23:59:59Z',
  drawQuota: 10,
  status: 'ACTIVE',
  prizes: [
    { id: 11, name: 'A', winProbability: 25 },
    { id: 12, name: 'B', winProbability: 25 },
    { id: 13, name: 'C', winProbability: 25 },
  ],
  noWinProbability: 25,
};

const mod360 = (deg: number | undefined) => (((deg ?? NaN) % 360) + 360) % 360;

describe('landingRotation', () => {
  const slices = buildWheelSlices(quarters);

  it('stops with the middle of the drawn Prize under the top pointer', () => {
    // B 的中點 135° 要轉到正上方 → 順時針轉 360 − 135 = 225°
    expect(mod360(landingRotation(slices, 12, 0))).toBeCloseTo(225);
    expect(mod360(landingRotation(slices, 11, 0))).toBeCloseTo(315);
    expect(mod360(landingRotation(slices, 13, 0))).toBeCloseTo(135);
  });

  it('stops on 銘謝惠顧 when the Draw Result has no Prize', () => {
    expect(mod360(landingRotation(slices, null, 0))).toBeCloseTo(45);
  });

  it('always spins forward several full turns from where the wheel currently is', () => {
    const first = landingRotation(slices, 12, 0)!;
    const second = landingRotation(slices, 12, first)!;

    expect(first).toBeGreaterThanOrEqual(5 * 360);
    expect(second - first).toBeGreaterThanOrEqual(5 * 360);
    expect(mod360(second)).toBeCloseTo(225);
  });

  it('returns undefined for a Prize that is not on the wheel', () => {
    expect(landingRotation(slices, 99, 0)).toBeUndefined();
  });

  it('lands inside a narrow slice that was widened to the minimum sweep', () => {
    const narrow = buildWheelSlices({
      ...quarters,
      prizes: [
        { id: 1, name: '藍牙耳機', winProbability: 1 },
        { id: 2, name: '100 元購物金', winProbability: 9.5 },
        { id: 3, name: '免運券', winProbability: 30 },
      ],
      noWinProbability: 59.5,
    });

    for (const prizeId of [1, 2, 3, null]) {
      expect(sliceAtPointer(narrow, landingRotation(narrow, prizeId, 123)!)?.prizeId).toBe(prizeId);
    }
  });
});

describe('sliceAtPointer', () => {
  const slices = buildWheelSlices(quarters);

  it('reads the slice under the top pointer after the wheel turned clockwise', () => {
    expect(sliceAtPointer(slices, 0)?.label).toBe('A');
    // 順時針轉 100°：正上方是原本 260° 的位置（C）
    expect(sliceAtPointer(slices, 100)?.label).toBe('C');
    expect(sliceAtPointer(slices, 360 * 3 + 300)?.label).toBe('A');
    expect(sliceAtPointer(slices, 200)?.label).toBe('B');
  });
});
