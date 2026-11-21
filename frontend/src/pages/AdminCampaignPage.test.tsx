import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import type { AdminCampaign, CampaignStats } from '../api/adminCampaigns';
import { AdminCampaignPage } from './AdminCampaignPage';

function json(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

function campaign(overrides: Partial<AdminCampaign> = {}): AdminCampaign {
  return {
    id: 7,
    name: '雙十一轉盤',
    startAt: '2026-12-01T02:00:00Z',
    endAt: '2026-12-31T15:59:00Z',
    drawQuota: 3,
    status: 'DRAFT',
    prizes: [
      { id: 11, name: '藍牙耳機', winProbability: 33.3333, totalStock: 10, remainingStock: 8 },
      { id: 12, name: '免運券', winProbability: 33.3333, totalStock: 100, remainingStock: 100 },
    ],
    noWinProbability: 33.3334,
    ...overrides,
  };
}

const noStats: CampaignStats = { campaignId: 7, totalDraws: 0, noWinCount: 0, prizes: [] };

type Handler = (init: RequestInit) => Response;

/** 依「METHOD 路徑」回應；記錄每個請求。每次呼叫可回傳不同結果（陣列依序使用，最後一個重複）。 */
function fakeApi(routes: Record<string, Handler | Handler[]>) {
  const fetch = vi.fn(async (url: string, init: RequestInit) => {
    const key = `${init.method} ${url.replace('/api/v1', '')}`;
    const route = routes[key];
    if (!route) throw new Error(`unexpected request ${key}`);
    const handler = Array.isArray(route) ? (route.length > 1 ? route.shift()! : route[0]!) : route;
    return handler(init);
  });
  vi.stubGlobal('fetch', fetch);
  const calls = (key: string) =>
    fetch.mock.calls
      .filter(([url, init]) => `${init.method} ${url.replace('/api/v1', '')}` === key)
      .map(([, init]) => (init.body ? JSON.parse(init.body as string) : undefined));
  return { fetch, calls };
}

function renderPage() {
  render(
    <MemoryRouter initialEntries={['/admin/campaigns/7']}>
      <Routes>
        <Route path="/admin/campaigns/:campaignId" element={<AdminCampaignPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

function prizeRow(name: string) {
  const table = screen.getByRole('table', { name: 'Prize 列表' });
  return within(table).getByRole('row', { name: new RegExp(name) });
}

describe('AdminCampaignPage', () => {
  it('shows each Prize with its Stock and drawn count, including Prizes never drawn, and the total Draws', async () => {
    fakeApi({
      'GET /admin/campaigns/7': () => json(200, campaign({ status: 'ACTIVE' })),
      'GET /admin/campaigns/7/stats': () =>
        json(200, { campaignId: 7, totalDraws: 6, noWinCount: 4, prizes: [{ prizeId: 11, prizeName: '藍牙耳機', drawnCount: 2 }] }),
    });
    renderPage();

    expect(await screen.findByRole('heading', { name: '雙十一轉盤' })).toBeInTheDocument();
    expect(await screen.findByTestId('total-draws')).toHaveTextContent('6');
    expect(screen.getByTestId('no-win-count')).toHaveTextContent('4');
    expect(prizeRow('藍牙耳機')).toHaveTextContent(/33\.3333%.*8 \/ 10.*2/);
    expect(prizeRow('免運券')).toHaveTextContent(/33\.3333%.*100 \/ 100.*0/);
    expect(screen.getByTestId('probability-summary')).toHaveTextContent('Prize 總和 66.6666%');
    expect(screen.getByTestId('probability-summary')).toHaveTextContent('銘謝惠顧 33.3334%');
  });

  it('updates the probability sum and 銘謝惠顧 while typing a new Prize and blocks a total over 100%', async () => {
    const api = fakeApi({
      'GET /admin/campaigns/7': () => json(200, campaign()),
      'GET /admin/campaigns/7/stats': () => json(200, noStats),
    });
    renderPage();
    const form = within(await screen.findByRole('form', { name: '新增 Prize' }));

    await userEvent.type(form.getByLabelText('Win Probability（%）'), '33.3334');
    expect(form.getByTestId('probability-summary')).toHaveTextContent('Prize 總和 100%');
    expect(form.getByTestId('probability-summary')).toHaveTextContent('銘謝惠顧 0%');

    await userEvent.clear(form.getByLabelText('Win Probability（%）'));
    await userEvent.type(form.getByLabelText('Win Probability（%）'), '33.3335');
    expect(form.getByTestId('probability-summary')).toHaveTextContent('Prize 總和 100.0001%');
    expect(form.getByLabelText('Win Probability（%）')).toHaveAccessibleDescription(/總和超過 100%/);
    expect(form.getByRole('button', { name: '新增 Prize' })).toBeDisabled();

    await userEvent.clear(form.getByLabelText('Win Probability（%）'));
    await userEvent.type(form.getByLabelText('Win Probability（%）'), '1.23456');
    expect(form.getByLabelText('Win Probability（%）')).toHaveAccessibleDescription(/最多 4 位小數/);
    expect(form.getByRole('button', { name: '新增 Prize' })).toBeDisabled();
    expect(api.calls('POST /admin/campaigns/7/prizes')).toEqual([]);
  });

  it('adds a Prize with its exact percent and shows it', async () => {
    const after = campaign({
      prizes: [...campaign().prizes, { id: 13, name: '貼紙', winProbability: 0.0001, totalStock: 5, remainingStock: 5 }],
      noWinProbability: 33.3333,
    });
    const api = fakeApi({
      'GET /admin/campaigns/7': [() => json(200, campaign()), () => json(200, after)],
      'GET /admin/campaigns/7/stats': () => json(200, noStats),
      'POST /admin/campaigns/7/prizes': () => json(201, after.prizes[2]),
    });
    renderPage();
    const form = within(await screen.findByRole('form', { name: '新增 Prize' }));

    await userEvent.type(form.getByLabelText('名稱'), '貼紙');
    await userEvent.type(form.getByLabelText('Stock'), '5');
    await userEvent.type(form.getByLabelText('Win Probability（%）'), '0.0001');
    await userEvent.click(form.getByRole('button', { name: '新增 Prize' }));

    expect(await screen.findByRole('row', { name: /貼紙/ })).toHaveTextContent(/0\.0001%.*5 \/ 5/);
    expect(api.calls('POST /admin/campaigns/7/prizes')).toEqual([{ name: '貼紙', stock: 5, winProbability: 0.0001 }]);
    expect(form.getByLabelText('名稱')).toHaveValue('');
  });

  it('lets an ACTIVE Campaign only adjust Prizes, end and rebuild its live state', async () => {
    fakeApi({
      'GET /admin/campaigns/7': () => json(200, campaign({ status: 'ACTIVE' })),
      'GET /admin/campaigns/7/stats': () => json(200, noStats),
    });
    renderPage();
    await screen.findByRole('heading', { name: '雙十一轉盤' });

    expect(screen.getByRole('button', { name: '上線' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '結束' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '重建 Redis live state' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '儲存設定' })).toBeDisabled();
    expect(screen.queryByRole('form', { name: '新增 Prize' })).not.toBeInTheDocument();
    expect(within(prizeRow('藍牙耳機')).queryByRole('button', { name: '刪除' })).not.toBeInTheDocument();
    expect(within(prizeRow('藍牙耳機')).getByRole('button', { name: '修改' })).toBeEnabled();
    expect(within(prizeRow('藍牙耳機')).getByRole('button', { name: '套用' })).toBeInTheDocument();
  });

  it('allows nothing on an ENDED Campaign', async () => {
    fakeApi({
      'GET /admin/campaigns/7': () => json(200, campaign({ status: 'ENDED' })),
      'GET /admin/campaigns/7/stats': () => json(200, noStats),
    });
    renderPage();
    await screen.findByRole('heading', { name: '雙十一轉盤' });

    for (const name of ['上線', '結束', '重建 Redis live state', '儲存設定']) {
      expect(screen.getByRole('button', { name })).toBeDisabled();
    }
    expect(within(prizeRow('藍牙耳機')).queryByRole('button')).not.toBeInTheDocument();
  });

  it('activates a DRAFT Campaign and shows why the backend refused when it does', async () => {
    const api = fakeApi({
      'GET /admin/campaigns/7': () => json(200, campaign()),
      'GET /admin/campaigns/7/stats': () => json(200, noStats),
      'POST /admin/campaigns/7/activate': [
        () => json(409, { title: 'Conflict', status: 409, code: 'CAMPAIGN_HAS_NO_PRIZE', detail: 'Campaign 7 has no prize' }),
        () => json(200, campaign({ status: 'ACTIVE' })),
      ],
    });
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: '上線' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Campaign 至少需要一個 Prize 才能上線');
    expect(alert).toHaveTextContent('Campaign 7 has no prize');

    await userEvent.click(screen.getByRole('button', { name: '上線' }));
    expect(await screen.findByText(/ACTIVE 已上線/)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(api.calls('POST /admin/campaigns/7/activate')).toHaveLength(2);
  });

  it('edits a Prize and shows a backend PROBABILITY_SUM_EXCEEDED next to its Win Probability', async () => {
    const api = fakeApi({
      'GET /admin/campaigns/7': () => json(200, campaign({ status: 'ACTIVE' })),
      'GET /admin/campaigns/7/stats': () => json(200, noStats),
      'PUT /admin/campaigns/7/prizes/11': () =>
        json(409, { title: 'Conflict', status: 409, code: 'PROBABILITY_SUM_EXCEEDED', detail: 'Sum would be 100.0001%' }),
    });
    renderPage();
    await screen.findByRole('heading', { name: '雙十一轉盤' });

    await userEvent.click(within(prizeRow('藍牙耳機')).getByRole('button', { name: '修改' }));
    const form = within(screen.getByRole('form', { name: '修改 Prize：藍牙耳機' }));
    const probability = form.getByLabelText('Win Probability（%）');
    expect(probability).toHaveValue('33.3333');

    // 只換掉這個 Prize 自己的機率：33.3333 → 66.6667，總和 100%
    await userEvent.clear(probability);
    await userEvent.type(probability, '66.6667');
    expect(form.getByTestId('probability-summary')).toHaveTextContent('Prize 總和 100%');
    await userEvent.click(form.getByRole('button', { name: '儲存' }));

    await waitFor(() => expect(probability).toHaveAttribute('aria-invalid', 'true'));
    expect(probability).toHaveAccessibleDescription('所有 Prize 的 Win Probability 總和不可超過 100%');
    expect(screen.getByRole('alert')).toHaveTextContent('Sum would be 100.0001%');
    expect(api.calls('PUT /admin/campaigns/7/prizes/11')).toEqual([{ name: '藍牙耳機', winProbability: 66.6667 }]);
  });

  it('adjusts Stock by a delta and blocks a delta that would make it negative', async () => {
    const adjusted = campaign({
      status: 'ACTIVE',
      prizes: [{ ...campaign().prizes[0]!, totalStock: 60, remainingStock: 58 }, campaign().prizes[1]!],
    });
    const api = fakeApi({
      'GET /admin/campaigns/7': [() => json(200, campaign({ status: 'ACTIVE' })), () => json(200, adjusted)],
      'GET /admin/campaigns/7/stats': () => json(200, noStats),
      'PATCH /admin/campaigns/7/prizes/11/stock': () => json(200, adjusted.prizes[0]),
    });
    renderPage();
    await screen.findByRole('heading', { name: '雙十一轉盤' });
    const row = within(prizeRow('藍牙耳機'));

    await userEvent.type(row.getByLabelText('Stock 增減量'), '-9');
    expect(row.getByLabelText('Stock 增減量')).toHaveAccessibleDescription('Stock 不可變成負數（剩餘 8）');
    expect(row.getByRole('button', { name: '套用' })).toBeDisabled();

    await userEvent.clear(row.getByLabelText('Stock 增減量'));
    await userEvent.type(row.getByLabelText('Stock 增減量'), '+50');
    await userEvent.click(row.getByRole('button', { name: '套用' }));

    expect(await screen.findByText('58 / 60')).toBeInTheDocument();
    expect(api.calls('PATCH /admin/campaigns/7/prizes/11/stock')).toEqual([{ delta: 50 }]);
  });

  it('deletes a Prize from a DRAFT Campaign after confirmation', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    const api = fakeApi({
      'GET /admin/campaigns/7': [
        () => json(200, campaign()),
        () => json(200, campaign({ prizes: [campaign().prizes[1]!], noWinProbability: 66.6667 })),
      ],
      'GET /admin/campaigns/7/stats': () => json(200, noStats),
      'DELETE /admin/campaigns/7/prizes/11': () => new Response(null, { status: 204 }),
    });
    renderPage();
    await screen.findByRole('heading', { name: '雙十一轉盤' });

    await userEvent.click(within(prizeRow('藍牙耳機')).getByRole('button', { name: '刪除' }));

    expect(await screen.findByText('銘謝惠顧 66.6667%')).toBeInTheDocument();
    expect(screen.queryByRole('row', { name: /藍牙耳機/ })).not.toBeInTheDocument();
    expect(api.calls('DELETE /admin/campaigns/7/prizes/11')).toHaveLength(1);
  });
});
