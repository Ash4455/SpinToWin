import type { WheelSlice } from './wheelSlices';

const PRIZE_COLORS = ['#f94144', '#f8961e', '#f9c74f', '#90be6d', '#43aa8b', '#4d908e', '#577590', '#9b5de5'];
const NO_WIN_COLOR = '#c9ccd1';

const SIZE = 320;
const CENTER = SIZE / 2;
const RADIUS = SIZE / 2 - 8;

export function sliceColor(slice: WheelSlice, index: number): string {
  return slice.prizeId === null ? NO_WIN_COLOR : (PRIZE_COLORS[index % PRIZE_COLORS.length] ?? NO_WIN_COLOR);
}

/** 角度 0 在正上方、順時針 */
function point(angleDeg: number, r: number): [number, number] {
  const rad = ((angleDeg - 90) * Math.PI) / 180;
  return [CENTER + r * Math.cos(rad), CENTER + r * Math.sin(rad)];
}

function slicePath(slice: WheelSlice): string {
  const sweep = slice.endAngle - slice.startAngle;
  if (sweep >= 359.999) {
    return `M ${CENTER - RADIUS} ${CENTER} a ${RADIUS} ${RADIUS} 0 1 0 ${RADIUS * 2} 0 a ${RADIUS} ${RADIUS} 0 1 0 ${-RADIUS * 2} 0`;
  }
  const [x1, y1] = point(slice.startAngle, RADIUS);
  const [x2, y2] = point(slice.endAngle, RADIUS);
  const largeArc = sweep > 180 ? 1 : 0;
  return `M ${CENTER} ${CENTER} L ${x1} ${y1} A ${RADIUS} ${RADIUS} 0 ${largeArc} 1 ${x2} ${y2} Z`;
}

interface Props {
  slices: WheelSlice[];
  /** 轉盤目前旋轉的角度（度），抽獎動畫用 */
  rotation?: number;
  /** 轉到新 rotation 的動畫時間（毫秒）；0 表示不做動畫 */
  spinMs?: number;
}

/** 轉盤：各格依 slices 的角度畫出，指針在正上方。 */
export function Wheel({ slices, rotation = 0, spinMs = 0 }: Props) {
  return (
    <figure className="wheel">
      <svg viewBox={`0 0 ${SIZE} ${SIZE}`} role="img" aria-label="抽獎轉盤" className="wheel__svg">
        <g
          style={{
            transform: `rotate(${rotation}deg)`,
            transformOrigin: 'center',
            transition: spinMs > 0 ? `transform ${spinMs}ms cubic-bezier(0.17, 0.67, 0.2, 1)` : undefined,
          }}
          className="wheel__disc"
          data-testid="wheel-disc"
        >
          {slices.map((slice, i) => {
            const mid = (slice.startAngle + slice.endAngle) / 2;
            const [tx, ty] = point(mid, RADIUS * 0.62);
            return (
              <g key={slice.key} data-testid="wheel-slice">
                <path d={slicePath(slice)} fill={sliceColor(slice, i)} stroke="#fff" strokeWidth={2} />
                <text
                  x={tx}
                  y={ty}
                  className="wheel__label"
                  textAnchor="middle"
                  dominantBaseline="middle"
                  transform={`rotate(${mid} ${tx} ${ty})`}
                >
                  {slice.label}
                </text>
              </g>
            );
          })}
        </g>
        <circle cx={CENTER} cy={CENTER} r={18} className="wheel__hub" />
        <polygon points={`${CENTER - 12},2 ${CENTER + 12},2 ${CENTER},30`} className="wheel__pointer" />
      </svg>
    </figure>
  );
}
