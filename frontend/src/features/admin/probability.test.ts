import { describe, expect, it } from 'vitest';
import { parsePercentToPpm, percentToPpm, ppmToPercentText, probabilityBreakdown } from './probability';

describe('parsePercentToPpm', () => {
  it('converts a percent with up to 4 decimals to exact ppm', () => {
    expect(parsePercentToPpm('1')).toBe(10_000);
    expect(parsePercentToPpm('9.5')).toBe(95_000);
    expect(parsePercentToPpm('0.0001')).toBe(1);
    expect(parsePercentToPpm('33.3333')).toBe(333_333);
    expect(parsePercentToPpm('100')).toBe(1_000_000);
    expect(parsePercentToPpm(' 0.1 ')).toBe(1_000);
  });

  it.each(['', 'abc', '-1', '1.23456', '100.0001', '101', '1e2', '1.', '.5', '1,5'])(
    'rejects %j (not a percent between 0 and 100 with up to 4 decimals)',
    (input) => {
      expect(parsePercentToPpm(input)).toBeNull();
    },
  );
});

describe('ppmToPercentText', () => {
  it('shows ppm as a percent without trailing zeros', () => {
    expect(ppmToPercentText(10_000)).toBe('1');
    expect(ppmToPercentText(95_000)).toBe('9.5');
    expect(ppmToPercentText(1)).toBe('0.0001');
    expect(ppmToPercentText(0)).toBe('0');
    expect(ppmToPercentText(1_000_000)).toBe('100');
    expect(ppmToPercentText(-5_000)).toBe('-0.5');
  });
});

describe('percentToPpm', () => {
  it('reads the API percent number back to ppm without float drift', () => {
    // 0.1 + 0.2 之類的浮點值在 JSON 中仍是最接近的 double，四捨五入回 ppm 就是精確值
    expect(percentToPpm(0.0001)).toBe(1);
    expect(percentToPpm(33.3333)).toBe(333_333);
    expect(percentToPpm(0.1 + 0.2)).toBe(3_000);
  });
});

describe('probabilityBreakdown', () => {
  it('sums Prize ppm exactly and gives 銘謝惠顧 the remainder', () => {
    // 浮點加總 33.3333 * 3 會是 99.99990000000001
    expect(probabilityBreakdown([333_333, 333_333, 333_333])).toEqual({
      prizesPpm: 999_999,
      noWinPpm: 1,
      exceeded: false,
    });
  });

  it('is exactly 100% without 銘謝惠顧 when Prizes fill it', () => {
    expect(probabilityBreakdown([10_000, 990_000])).toEqual({ prizesPpm: 1_000_000, noWinPpm: 0, exceeded: false });
  });

  it('flags a sum over 100% and reports the excess as a negative remainder', () => {
    expect(probabilityBreakdown([600_000, 400_001])).toEqual({ prizesPpm: 1_000_001, noWinPpm: -1, exceeded: true });
  });

  it('gives 100% 銘謝惠顧 without Prizes', () => {
    expect(probabilityBreakdown([])).toEqual({ prizesPpm: 0, noWinPpm: 1_000_000, exceeded: false });
  });
});
