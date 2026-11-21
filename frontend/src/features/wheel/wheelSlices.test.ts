import { describe, expect, it } from 'vitest';
import type { Campaign } from '../../api/campaigns';
import { buildWheelSlices } from './wheelSlices';

function campaign(prizes: Campaign['prizes'], noWinProbability: number): Campaign {
  return {
    id: 1,
    name: '週年慶轉盤抽獎',
    startAt: '2026-01-01T00:00:00Z',
    endAt: '2030-12-31T23:59:59Z',
    drawQuota: 10,
    status: 'ACTIVE',
    prizes,
    noWinProbability,
  };
}

const seedCampaign = campaign(
  [
    { id: 1, name: '藍牙耳機', winProbability: 1 },
    { id: 2, name: '100 元購物金', winProbability: 9.5 },
    { id: 3, name: '免運券', winProbability: 30 },
  ],
  59.5,
);

describe('buildWheelSlices', () => {
  it('has one slice per Prize in Prize order, then a 銘謝惠顧 slice', () => {
    const slices = buildWheelSlices(seedCampaign);

    expect(slices.map((s) => [s.prizeId, s.label, s.probability])).toEqual([
      [1, '藍牙耳機', 1],
      [2, '100 元購物金', 9.5],
      [3, '免運券', 30],
      [null, '銘謝惠顧', 59.5],
    ]);
  });

  it('gives each slice a contiguous sweep proportional to its probability, covering the full circle', () => {
    const slices = buildWheelSlices(
      campaign(
        [
          { id: 7, name: 'A', winProbability: 25 },
          { id: 8, name: 'B', winProbability: 25 },
        ],
        50,
      ),
    );

    expect(slices.map((s) => [s.startAngle, s.endAngle])).toEqual([
      [0, 90],
      [90, 180],
      [180, 360],
    ]);
  });

  it('widens a tiny slice to the minimum sweep and shrinks the others proportionally', () => {
    // 藍牙耳機 1% 照比例只有 3.6°，放大到 8°；其餘 352° 依 9.5 : 30 : 59.5 分配
    const sweeps = buildWheelSlices(seedCampaign).map((s) => s.endAngle - s.startAngle);

    expect(sweeps[0]).toBeCloseTo(8, 4);
    expect(sweeps[1]).toBeCloseTo(33.7778, 4);
    expect(sweeps[2]).toBeCloseTo(106.6667, 4);
    expect(sweeps[3]).toBeCloseTo(211.5556, 4);
  });

  it('keeps a visible 銘謝惠顧 slice even when the Prizes add up to 100%', () => {
    // 抽中 Stock 為 0 的 Prize 也會是銘謝惠顧，所以轉盤上一定要有這一格
    const slices = buildWheelSlices(
      campaign(
        [
          { id: 1, name: 'A', winProbability: 50 },
          { id: 2, name: 'B', winProbability: 50 },
        ],
        0,
      ),
    );

    expect(slices.map((s) => [s.label, s.startAngle, s.endAngle])).toEqual([
      ['A', 0, 176],
      ['B', 176, 352],
      ['銘謝惠顧', 352, 360],
    ]);
  });
});
