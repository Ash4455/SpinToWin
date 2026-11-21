import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { PAUSE_MS, RETRY_DELAYS_MS, spinDurationMs } from '../features/draw/useDrawSession';
import { WheelPage } from './WheelPage';

/** 三個 Prize 各 25%、銘謝惠顧 25%：每格 90°。停在 A/B/C/銘謝惠顧 時轉盤角度 mod 360 為 315/225/135/45。 */
const CAMPAIGN = {
  id: 1,
  name: '週年慶轉盤抽獎',
  startAt: '2026-01-01T00:00:00Z',
  endAt: '2030-12-31T23:59:59Z',
  drawQuota: 10,
  status: 'ACTIVE',
  prizes: [
    { id: 11, name: '藍牙耳機', winProbability: 25 },
    { id: 12, name: '100 元購物金', winProbability: 25 },
    { id: 13, name: '免運券', winProbability: 25 },
  ],
  noWinProbability: 25,
};
const STOP_ANGLE = { 11: 315, 12: 225, 13: 135, none: 45 } as const;

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });

const problem = (status: number, code: string, detail = '') =>
  json(status, { title: 'Conflict', status, detail, code });

const result = (drawId: string, prizeId: keyof typeof STOP_ANGLE) =>
  prizeId === 'none'
    ? { drawId, prizeId: null, prizeName: null, won: false }
    : { drawId, prizeId, prizeName: CAMPAIGN.prizes.find((p) => p.id === prizeId)!.name, won: true };

const drawReply = (remainingDraws: number, ...prizeIds: (keyof typeof STOP_ANGLE)[]) =>
  json(200, {
    requestId: 'req-1',
    drawnAt: '2026-11-21T03:00:00Z',
    remainingDraws,
    results: prizeIds.map((p, i) => result(`req-1-${i + 1}`, p)),
  });

type Reply = Response | Error | (() => Response | Error);

/** fetch 是網路邊界：依 method + path 回應，抽獎請求依序取 drawReplies。 */
function fakeBackend({
  remaining = 10,
  records = [] as unknown[],
  drawReplies = [] as Reply[],
  recordReplies = [] as Reply[],
} = {}) {
  const drawRequests: { count: number; idempotencyKey: string | null }[] = [];
  const fetch = vi.fn(async (input: string, init: RequestInit = {}) => {
    const url = new URL(input, 'http://localhost');
    const method = init.method ?? 'GET';
    const take = (queue: Reply[], fallback: () => Response) => {
      const next = queue.shift();
      const reply = typeof next === 'function' ? next() : next;
      if (reply instanceof Error) throw reply;
      return reply ?? fallback();
    };
    if (method === 'GET' && url.pathname === '/api/v1/campaigns/1') return json(200, CAMPAIGN);
    if (method === 'GET' && url.pathname === '/api/v1/campaigns/1/me')
      return json(200, { campaignId: 1, drawQuota: 10, usedDraws: 10 - remaining, remainingDraws: remaining });
    if (method === 'GET' && url.pathname === '/api/v1/campaigns/1/draws/me')
      return take(recordReplies, () =>
        json(200, { content: records, page: 0, size: 10, totalElements: records.length, totalPages: 1 }),
      );
    if (method === 'POST' && url.pathname === '/api/v1/campaigns/1/draws') {
      drawRequests.push({
        count: (JSON.parse(String(init.body)) as { count: number }).count,
        idempotencyKey: new Headers(init.headers).get('Idempotency-Key'),
      });
      return take(drawReplies, () => {
        throw new Error('unexpected draw request');
      });
    }
    throw new Error(`unexpected ${method} ${input}`);
  });
  vi.stubGlobal('fetch', fetch);
  return { fetch, drawRequests };
}

async function renderPage() {
  const view = render(
    <MemoryRouter initialEntries={['/campaigns/1']}>
      <Routes>
        <Route path="/campaigns/:campaignId" element={<WheelPage />} />
      </Routes>
    </MemoryRouter>,
  );
  await screen.findByRole('heading', { name: '週年慶轉盤抽獎' });
  await screen.findByTestId('remaining-draws');
  // 「我的紀錄」是另一個請求，可能比活動與剩餘次數晚回來：等它也載入完成，否則測試會偶爾看到「載入中…」
  const records = screen.getByRole('region', { name: '我的抽獎紀錄' });
  await waitFor(() => expect(within(records).queryByText('載入中…')).not.toBeInTheDocument());
  // 載入完成後才改用假時鐘，轉盤動畫的時間由測試推進
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  return view;
}

afterEach(() => {
  vi.useRealTimers();
});

const mod360 = (deg: number) => ((deg % 360) + 360) % 360;

function wheelStopAngle() {
  const transform = screen.getByTestId('wheel-disc').style.transform;
  const deg = Number(/rotate\((-?[\d.]+)deg\)/.exec(transform)?.[1]);
  return mod360(deg);
}

async function click(name: RegExp | string) {
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name }));
  });
  // 讓 fetch 的 promise 完成
  await act(() => vi.advanceTimersByTimeAsync(0));
}

async function finishSpin(count = 1) {
  await act(() => vi.advanceTimersByTimeAsync(spinDurationMs(count)));
}

describe('WheelPage drawing', () => {
  it('single draw: the wheel stops on the drawn Prize and the Draw Result is shown', async () => {
    const { drawRequests } = fakeBackend({ drawReplies: [drawReply(9, 12)] });
    await renderPage();

    await click('單抽');

    expect(drawRequests).toEqual([{ count: 1, idempotencyKey: expect.any(String) }]);
    expect(wheelStopAngle()).toBeCloseTo(STOP_ANGLE[12]);
    expect(screen.getByRole('status', { name: '抽獎結果' })).not.toHaveTextContent('100 元購物金');

    await finishSpin();

    expect(screen.getByRole('status', { name: '抽獎結果' })).toHaveTextContent('恭喜抽中 100 元購物金');
  });

  it('multi-draw: plays each Draw Result in order on the wheel, then shows a summary', async () => {
    const { drawRequests } = fakeBackend({ drawReplies: [drawReply(7, 13, 'none', 11)] });
    await renderPage();

    fireEvent.change(screen.getByRole('combobox', { name: '連抽次數' }), { target: { value: '3' } });
    await click('連抽 3 次');
    expect(drawRequests).toEqual([{ count: 3, idempotencyKey: expect.any(String) }]);
    const panel = () => screen.getByRole('status', { name: '抽獎結果' });

    // 第 1 抽：免運券
    expect(wheelStopAngle()).toBeCloseTo(STOP_ANGLE[13]);
    expect(panel()).toHaveTextContent('第 1 / 3 抽');
    expect(panel()).not.toHaveTextContent('免運券');
    await finishSpin(3);
    expect(panel()).toHaveTextContent('第 1 抽：免運券');
    expect(panel()).not.toHaveTextContent('第 2 抽');

    // 第 2 抽：銘謝惠顧
    await act(() => vi.advanceTimersByTimeAsync(PAUSE_MS));
    expect(wheelStopAngle()).toBeCloseTo(STOP_ANGLE.none);
    expect(panel()).toHaveTextContent('第 2 / 3 抽');
    await finishSpin(3);
    expect(panel()).toHaveTextContent('第 2 抽：銘謝惠顧');
    expect(panel()).not.toHaveTextContent('第 3 抽');

    // 第 3 抽：藍牙耳機，然後總結
    await act(() => vi.advanceTimersByTimeAsync(PAUSE_MS));
    expect(wheelStopAngle()).toBeCloseTo(STOP_ANGLE[11]);
    await finishSpin(3);
    expect(panel()).toHaveTextContent('連抽 3 次完成：中獎 2 次、銘謝惠顧 1 次');
    expect(
      within(screen.getByRole('list', { name: '連抽結果' }))
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual(['第 1 抽：免運券', '第 2 抽：銘謝惠顧', '第 3 抽：藍牙耳機']);
  });

  it('multi-draw: skipping the animation reveals every remaining Draw Result at once', async () => {
    fakeBackend({ drawReplies: [drawReply(7, 13, 'none', 11)] });
    await renderPage();

    fireEvent.change(screen.getByRole('combobox', { name: '連抽次數' }), { target: { value: '3' } });
    await click('連抽 3 次');
    await click('略過動畫');

    expect(screen.getByRole('status', { name: '抽獎結果' })).toHaveTextContent('連抽 3 次完成');
    expect(wheelStopAngle()).toBeCloseTo(STOP_ANGLE[11]);
    expect(
      within(screen.getByRole('list', { name: '抽獎紀錄列表' }))
        .getAllByRole('listitem')
        .map((li) => li.querySelector('.records__result')?.textContent),
    ).toEqual(['藍牙耳機', '銘謝惠顧', '免運券']);
  });

  it('updates the remaining Draw Quota as soon as the draw response arrives', async () => {
    fakeBackend({ remaining: 5, drawReplies: [drawReply(2, 13, 'none', 11)] });
    await renderPage();
    expect(screen.getByTestId('remaining-draws')).toHaveTextContent('5');

    fireEvent.change(screen.getByRole('combobox', { name: '連抽次數' }), { target: { value: '3' } });
    await click('連抽 3 次');

    expect(screen.getByTestId('remaining-draws')).toHaveTextContent('2');
  });

  it('offers Multi-Draw counts up to 10 and never more than the remaining Draw Quota', async () => {
    fakeBackend({ remaining: 4 });
    await renderPage();

    const options = within(screen.getByRole('combobox', { name: '連抽次數' }))
      .getAllByRole('option')
      .map((o) => o.textContent);
    expect(options).toEqual(['2 次', '3 次', '4 次']);
    expect(screen.getByRole('button', { name: '連抽 4 次' })).toBeEnabled();
  });

  it('caps Multi-Draw at 10 when more draws remain', async () => {
    fakeBackend({ remaining: 25 });
    await renderPage();

    expect(within(screen.getByRole('combobox', { name: '連抽次數' })).getAllByRole('option').at(-1)).toHaveTextContent(
      '10 次',
    );
  });

  it('disables drawing when the Draw Quota is used up', async () => {
    fakeBackend({ remaining: 0 });
    await renderPage();

    expect(screen.getByRole('button', { name: '單抽' })).toBeDisabled();
    expect(screen.getByRole('button', { name: /^連抽/ })).toBeDisabled();
    expect(screen.getByText('抽獎次數已用完。')).toBeInTheDocument();
  });

  it('disables the draw buttons while drawing and re-enables them after the wheel stops', async () => {
    fakeBackend({ drawReplies: [drawReply(9, 12)] });
    await renderPage();

    await click('單抽');
    expect(screen.getByRole('button', { name: '單抽' })).toBeDisabled();
    expect(screen.getByRole('button', { name: /^連抽/ })).toBeDisabled();

    await finishSpin();
    expect(screen.getByRole('button', { name: '單抽' })).toBeEnabled();
  });

  it.each([
    ['QUOTA_EXCEEDED', problem(409, 'QUOTA_EXCEEDED', 'Requested 3 draws but only 2 remain.'), '抽獎次數不足'],
    ['CAMPAIGN_NOT_ACTIVE', problem(409, 'CAMPAIGN_NOT_ACTIVE', 'Campaign 1 has ended.'), '活動目前未開放抽獎'],
    ['RATE_LIMITED', problem(429, 'RATE_LIMITED'), '操作太頻繁'],
  ])('shows a clear message for %s', async (_code, reply, message) => {
    fakeBackend({ drawReplies: [reply] });
    await renderPage();

    await click('單抽');

    expect(screen.getByRole('alert')).toHaveTextContent(message);
    expect(screen.queryByRole('button', { name: '重新送出' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '單抽' })).toBeEnabled();
  });

  it('reloads the remaining Draw Quota after QUOTA_EXCEEDED', async () => {
    const { fetch } = fakeBackend({ drawReplies: [problem(409, 'QUOTA_EXCEEDED')] });
    await renderPage();
    const quotaCalls = () => fetch.mock.calls.filter(([url]) => String(url).endsWith('/campaigns/1/me')).length;
    expect(quotaCalls()).toBe(1);

    await click('單抽');

    expect(quotaCalls()).toBe(2);
  });

  it('retries a network failure automatically with the same Idempotency-Key', async () => {
    const { drawRequests } = fakeBackend({
      drawReplies: [new TypeError('Failed to fetch'), drawReply(9, 12)],
    });
    await renderPage();

    await click('單抽');
    await act(() => vi.advanceTimersByTimeAsync(RETRY_DELAYS_MS[0]!));

    expect(drawRequests).toHaveLength(2);
    expect(drawRequests[1]!.idempotencyKey).toBe(drawRequests[0]!.idempotencyKey);
    expect(wheelStopAngle()).toBeCloseTo(STOP_ANGLE[12]);
  });

  it('after the automatic retries fail, 重新送出 reuses the key while a new click gets a new key', async () => {
    const offline = () => new TypeError('Failed to fetch');
    const { drawRequests } = fakeBackend({
      drawReplies: [offline, offline, offline, drawReply(9, 12), drawReply(8, 'none')],
    });
    await renderPage();

    await click('單抽');
    for (const delay of RETRY_DELAYS_MS) await act(() => vi.advanceTimersByTimeAsync(delay));

    expect(drawRequests).toHaveLength(1 + RETRY_DELAYS_MS.length);
    expect(screen.getByRole('alert')).toHaveTextContent('無法確認抽獎結果');

    await click('重新送出');
    await finishSpin();
    await click('單抽');

    const keys = drawRequests.map((r) => r.idempotencyKey);
    expect(new Set(keys.slice(0, 4)).size).toBe(1);
    expect(keys[4]).not.toBe(keys[0]);
    expect(keys[4]).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });

  it('gives every user click a new Idempotency-Key', async () => {
    const { drawRequests } = fakeBackend({ drawReplies: [drawReply(9, 12), drawReply(8, 13)] });
    await renderPage();

    await click('單抽');
    await finishSpin();
    await click('單抽');

    expect(drawRequests[0]!.idempotencyKey).not.toBe(drawRequests[1]!.idempotencyKey);
  });

  it('my records: new Draw Results go on top right away, then the record-service data takes over', async () => {
    const older = { drawId: 'old-1', requestId: 'old', prizeId: 13, prizeName: '免運券', won: true, drawnAt: '2026-11-20T03:00:00Z' };
    const page = (content: unknown[]) =>
      json(200, { content, page: 0, size: 10, totalElements: content.length, totalPages: 1 });
    const { fetch } = fakeBackend({
      drawReplies: [drawReply(8, 12, 'none')],
      recordReplies: [
        page([older]),
        // 抽完後第一次重載：record-service 還沒寫入
        page([older]),
        // 之後：已寫入，且 Prize 名稱以 record-service 為準
        page([
          { drawId: 'req-1-2', requestId: 'req-1', prizeId: null, prizeName: null, won: false, drawnAt: '2026-11-21T03:00:00Z' },
          { drawId: 'req-1-1', requestId: 'req-1', prizeId: 12, prizeName: '購物金（改名）', won: true, drawnAt: '2026-11-21T03:00:00Z' },
          older,
        ]),
      ],
    });
    await renderPage();
    const list = () =>
      within(screen.getByRole('list', { name: '抽獎紀錄列表' }))
        .getAllByRole('listitem')
        .map((li) => li.querySelector('.records__result')?.textContent);
    expect(list()).toEqual(['免運券']);

    fireEvent.change(screen.getByRole('combobox', { name: '連抽次數' }), { target: { value: '2' } });
    await click('連抽 2 次');
    // 轉盤還沒停：不能先在紀錄裡爆雷
    expect(list()).toEqual(['免運券']);

    await finishSpin(2);
    expect(list()).toEqual(['100 元購物金', '免運券']);
    await act(() => vi.advanceTimersByTimeAsync(PAUSE_MS));
    await finishSpin(2);
    await act(() => vi.advanceTimersByTimeAsync(0));
    expect(list()).toEqual(['銘謝惠顧', '100 元購物金', '免運券']);

    const recordCalls = () => fetch.mock.calls.filter(([url]) => String(url).includes('/draws/me')).length;
    expect(recordCalls()).toBe(2);
    await act(() => vi.advanceTimersByTimeAsync(10_000));
    expect(recordCalls()).toBe(3);
    expect(list()).toEqual(['銘謝惠顧', '購物金（改名）', '免運券']);
  });

  it('my records: pages through the history', async () => {
    const record = (n: number) => ({
      drawId: `r-${n}`, requestId: `r-${n}`, prizeId: null, prizeName: null, won: false, drawnAt: '2026-11-20T03:00:00Z',
    });
    const { fetch } = fakeBackend({
      recordReplies: [
        json(200, { content: [record(1)], page: 0, size: 10, totalElements: 11, totalPages: 2 }),
        json(200, { content: [record(11)], page: 1, size: 10, totalElements: 11, totalPages: 2 }),
      ],
    });
    await renderPage();
    expect(screen.getByText('第 1 / 2 頁')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '上一頁' })).toBeDisabled();

    await click('下一頁');

    expect(fetch.mock.calls.at(-1)![0]).toBe('/api/v1/campaigns/1/draws/me?page=1&size=10');
    expect(screen.getByText('第 2 / 2 頁')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '下一頁' })).toBeDisabled();
  });
});
