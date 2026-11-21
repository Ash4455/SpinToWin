import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useParams } from 'react-router';
import { ApiError } from '../api/client';
import { getCampaign, type Campaign, type CampaignStatus } from '../api/campaigns';
import { getMyDrawQuota, type DrawResponse, type DrawResult } from '../api/draws';
import { getMyDrawRecords, type DrawRecord, type DrawRecordPage } from '../api/records';
import { useApiQuery } from '../api/useApiQuery';
import { ErrorMessage } from '../components/ErrorMessage';
import { DrawResultPanel } from '../features/draw/DrawResultPanel';
import { MyDrawRecords, RECORDS_PAGE_SIZE } from '../features/draw/MyDrawRecords';
import { spinDurationMs, useDrawSession } from '../features/draw/useDrawSession';
import { Wheel, sliceColor } from '../features/wheel/Wheel';
import { buildWheelSlices } from '../features/wheel/wheelSlices';
import { formatDateTime, formatPercent } from '../format';

const STATUS_LABEL: Record<CampaignStatus, string> = {
  DRAFT: '尚未開放',
  ACTIVE: '進行中',
  ENDED: '已結束',
};

/** Multi-Draw 一次最多幾抽（後端 count 上限） */
const MAX_DRAW_COUNT = 10;
/** 剛抽到的結果還沒出現在 record-service 時，隔多久再重載一次紀錄、最多幾次 */
export const RECORDS_CATCHUP_MS = 2000;
const RECORDS_CATCHUP_ATTEMPTS = 3;

/** 轉盤抽獎頁：/campaigns/:campaignId */
export function WheelPage() {
  const { campaignId = '' } = useParams();
  const query = useApiQuery((signal) => getCampaign(campaignId, signal), [campaignId]);

  if (query.status === 'loading') return <p className="muted">載入中…</p>;
  if (query.status === 'error') return <ErrorMessage error={query.error} onRetry={query.reload} />;
  return <CampaignWheel key={campaignId} campaignId={campaignId} campaign={query.data} />;
}

function CampaignWheel({ campaignId, campaign }: { campaignId: string; campaign: Campaign }) {
  const slices = useMemo(() => buildWheelSlices(campaign), [campaign]);

  // 剩餘次數：先以 /me 為準，抽獎回應一到就改用回應裡的 remainingDraws
  const quota = useApiQuery((signal) => getMyDrawQuota(campaignId, signal), [campaignId]);
  const [latestRemaining, setLatestRemaining] = useState<number | null>(null);
  const reloadQuotaQuery = quota.reload;
  const reloadQuota = useCallback(() => {
    setLatestRemaining(null);
    reloadQuotaQuery();
  }, [reloadQuotaQuery]);
  const remaining = latestRemaining ?? quota.data?.remainingDraws;

  // 我的紀錄：保留最近一次成功的資料，剛抽到的結果先插在最上面
  const [recordsPage, setRecordsPage] = useState(0);
  const records = useApiQuery(
    (signal) => getMyDrawRecords(campaignId, recordsPage, RECORDS_PAGE_SIZE, signal),
    [campaignId, recordsPage],
  );
  const [shownRecords, setShownRecords] = useState<DrawRecordPage>();
  const [justDrawn, setJustDrawn] = useState<DrawRecord[]>([]);
  // 新的一頁資料到了：顯示它，並移除已經寫入紀錄的剛抽結果（render 中調整 state，不另開 effect）
  if (records.status === 'success' && records.data !== shownRecords) {
    setShownRecords(records.data);
    if (records.data.page === 0) {
      const persisted = new Set(records.data.content.map((r) => r.requestId));
      setJustDrawn((prev) => prev.filter((r) => !persisted.has(r.requestId)));
    }
  }

  const session = useDrawSession({
    campaignId,
    slices,
    onDrawn: (response) => setLatestRemaining(response.remainingDraws),
    onRevealed: (result: DrawResult, response: DrawResponse) =>
      setJustDrawn((prev) => [{ ...result, requestId: response.requestId, drawnAt: response.drawnAt }, ...prev]),
    onFinished: () => records.reload(),
    onFailed: (error) => {
      if (error instanceof ApiError && error.code === 'QUOTA_EXCEEDED') reloadQuota();
    },
  });

  // record-service 是最終一致：剛抽到的結果還沒寫入就稍後再載一次（播放動畫時不載，以免先爆雷）
  const catchUpAttempts = useRef(0);
  const reloadRecords = records.reload;
  useEffect(() => {
    if (justDrawn.length === 0) {
      catchUpAttempts.current = 0;
      return;
    }
    if (session.busy || records.status !== 'success' || catchUpAttempts.current >= RECORDS_CATCHUP_ATTEMPTS) return;
    const timer = setTimeout(() => {
      catchUpAttempts.current += 1;
      reloadRecords();
    }, RECORDS_CATCHUP_MS);
    return () => clearTimeout(timer);
  }, [justDrawn, session.busy, records.status, reloadRecords]);

  // 正在播放的這次抽獎中還沒揭曉的結果，即使 record-service 已經有了也先不顯示
  const unrevealed = useMemo(() => {
    const phase = session.phase;
    if (phase.kind !== 'playing') return new Set<string>();
    const revealedCount = phase.index + (phase.landed ? 1 : 0);
    return new Set(phase.response.results.slice(revealedCount).map((r) => r.drawId));
  }, [session.phase]);

  const spinMs = session.phase.kind === 'playing' ? spinDurationMs(session.phase.response.results.length) : 0;

  return (
    <section className="campaign">
      <header className="campaign__header">
        <h1>{campaign.name}</h1>
        <p className="campaign__meta">
          <span className={`badge badge--${campaign.status.toLowerCase()}`}>{STATUS_LABEL[campaign.status]}</span>
          <span data-testid="campaign-time-window">
            {formatDateTime(campaign.startAt)} ～ {formatDateTime(campaign.endAt)}
          </span>
          <span>每人可抽 {campaign.drawQuota} 次</span>
        </p>
      </header>

      <div className="campaign__body">
        <div className="draw-stage">
          <Wheel slices={slices} rotation={session.rotation} spinMs={spinMs} />
          <DrawControls
            campaign={campaign}
            remaining={remaining}
            quotaError={quota.status === 'error' ? quota.error : undefined}
            onRetryQuota={reloadQuota}
            busy={session.busy}
            onDraw={session.draw}
          />
          <DrawResultPanel phase={session.phase} onResend={session.resend} onSkip={session.skip} />
        </div>
        <ul className="legend" aria-label="中獎機率">
          {slices.map((slice, i) => (
            <li key={slice.key} className="legend__item">
              <span className="legend__swatch" style={{ background: sliceColor(slice, i) }} aria-hidden />
              <span className="legend__label">{slice.label}</span>
              <span className="legend__value">{formatPercent(slice.probability)}</span>
            </li>
          ))}
        </ul>
      </div>

      <MyDrawRecords
        data={shownRecords}
        error={records.status === 'error' ? records.error : undefined}
        onRetry={records.reload}
        justDrawn={justDrawn}
        hiddenDrawIds={unrevealed}
        page={recordsPage}
        onPageChange={setRecordsPage}
      />
    </section>
  );
}

interface DrawControlsProps {
  campaign: Campaign;
  remaining: number | undefined;
  quotaError: unknown;
  onRetryQuota: () => void;
  busy: boolean;
  onDraw: (count: number) => void;
}

/** 單抽與連抽按鈕、剩餘次數。連抽次數最多 10 且不超過剩餘次數。 */
function DrawControls({ campaign, remaining, quotaError, onRetryQuota, busy, onDraw }: DrawControlsProps) {
  const [multiCount, setMultiCount] = useState(MAX_DRAW_COUNT);
  const maxMulti = Math.min(MAX_DRAW_COUNT, remaining ?? 0);
  const count = Math.min(multiCount, maxMulti);
  const open = campaign.status === 'ACTIVE';
  const canDraw = open && !busy && remaining !== undefined;

  return (
    <div className="draw-controls">
      <p className="draw-controls__quota">
        {remaining === undefined ? (
          quotaError ? null : '剩餘次數載入中…'
        ) : (
          <>
            剩餘 <strong data-testid="remaining-draws">{remaining}</strong> 次
            <span className="muted">（共 {campaign.drawQuota} 次）</span>
          </>
        )}
      </p>
      {quotaError !== undefined && remaining === undefined && (
        <ErrorMessage error={quotaError} onRetry={onRetryQuota} />
      )}
      <div className="draw-controls__buttons">
        <button type="button" className="button" disabled={!canDraw || remaining < 1} onClick={() => onDraw(1)}>
          單抽
        </button>
        <select
            aria-label="連抽次數"
            value={count}
            disabled={!canDraw || maxMulti < 2}
            onChange={(e) => setMultiCount(Number(e.target.value))}
          >
            {maxMulti >= 2 ? (
              Array.from({ length: maxMulti - 1 }, (_, i) => i + 2).map((n) => (
                <option key={n} value={n}>
                  {n} 次
                </option>
              ))
            ) : (
              <option value={count}>—</option>
            )}
          </select>
        <button
          type="button"
          className="button"
          disabled={!canDraw || maxMulti < 2}
          onClick={() => onDraw(count)}
        >
          {maxMulti >= 2 ? `連抽 ${count} 次` : '連抽'}
        </button>
      </div>
      {!open && <p className="muted">活動目前未開放抽獎。</p>}
      {open && remaining === 0 && <p className="muted">抽獎次數已用完。</p>}
    </div>
  );
}
