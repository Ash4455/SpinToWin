import { describe, expect, it } from 'vitest';
import type { Campaign } from '../../api/campaigns';
import { estimateTextWidth, sliceLabelLayout, type LabelGeometry } from './wheelLabels';
import { buildWheelSlices, type WheelSlice } from './wheelSlices';

const geometry: LabelGeometry = { center: 160, outerRadius: 140, innerRadius: 26, fontSize: 12 };

function slice(label: string, startAngle: number, endAngle: number): WheelSlice {
  return { key: label, prizeId: 1, label, probability: 1, startAngle, endAngle };
}

/** 文字在扇形內最窄處（靠圓心那一端）的厚度 */
function thicknessAtInnerEnd(s: WheelSlice, text: string): number {
  const innerEnd = geometry.outerRadius - estimateTextWidth(text, geometry.fontSize);
  const halfSweep = (((s.endAngle - s.startAngle) / 2) * Math.PI) / 180;
  return 2 * innerEnd * Math.sin(halfSweep);
}

const seedCampaign: Campaign = {
  id: 1,
  name: '週年慶轉盤抽獎',
  startAt: '2026-01-01T00:00:00Z',
  endAt: '2030-12-31T23:59:59Z',
  drawQuota: 10,
  status: 'ACTIVE',
  prizes: [
    { id: 1, name: '藍牙耳機', winProbability: 1 },
    { id: 2, name: '100 元購物金', winProbability: 9.5 },
    { id: 3, name: '免運券', winProbability: 30 },
  ],
  noWinProbability: 59.5,
};

describe('estimateTextWidth', () => {
  it('counts CJK characters as a full em and ASCII as roughly 0.6 em', () => {
    expect(estimateTextWidth('免運券', 12)).toBeCloseTo(36);
    expect(estimateTextWidth('100', 10)).toBeCloseTo(18);
  });
});

describe('sliceLabelLayout', () => {
  it('keeps every seed label whole and inside its own slice', () => {
    for (const s of buildWheelSlices(seedCampaign)) {
      const layout = sliceLabelLayout(s, geometry);
      expect(layout?.text).toBe(s.label);
      expect(thicknessAtInnerEnd(s, s.label)).toBeGreaterThanOrEqual(geometry.fontSize);
    }
  });

  it('shortens a long name with an ellipsis so it still fits a narrow slice', () => {
    const narrow = slice('超大容量行動電源組合包', 0, 10);
    const layout = sliceLabelLayout(narrow, geometry);

    expect(layout?.text).toMatch(/^超大.*…$/);
    expect(thicknessAtInnerEnd(narrow, layout!.text)).toBeGreaterThanOrEqual(geometry.fontSize);
  });

  it('hides the label when the slice is too thin for even one character', () => {
    expect(sliceLabelLayout(slice('藍牙耳機', 0, 3), geometry)).toBeNull();
  });

  it('runs the text along the radius and flips it on the left half so it is not upside down', () => {
    expect(sliceLabelLayout(slice('免運券', 0, 90), geometry)?.rotate).toBe(-45);
    expect(sliceLabelLayout(slice('免運券', 180, 270), geometry)?.rotate).toBe(315);
  });

  it('centres the text on the slice bisector between the hub and the rim', () => {
    const layout = sliceLabelLayout(slice('免運券', 80, 100), geometry)!;
    // 中線在正右方（90°）：y 與圓心同高，x 落在文字中點 = 外緣 - 文字長度一半
    expect(layout.y).toBeCloseTo(geometry.center);
    expect(layout.x).toBeCloseTo(geometry.center + geometry.outerRadius - estimateTextWidth('免運券', 12) / 2);
  });
});
