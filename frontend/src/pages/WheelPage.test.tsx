import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { WheelPage } from './WheelPage';

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/campaigns/:campaignId" element={<WheelPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

function stubFetch(status: number, body: unknown, contentType = 'application/json') {
  const fetch = vi.fn().mockResolvedValue(
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } }),
  );
  vi.stubGlobal('fetch', fetch);
  return fetch;
}

describe('WheelPage', () => {
  it('shows the Campaign name, its time window and a wheel with every Prize plus 銘謝惠顧', async () => {
    const fetch = stubFetch(200, {
      id: 1,
      name: '週年慶轉盤抽獎',
      startAt: '2026-01-01T00:00:00Z',
      endAt: '2030-12-31T23:59:59Z',
      drawQuota: 10,
      status: 'ACTIVE',
      prizes: [
        { id: 1, name: '藍牙耳機', winProbability: 1.0 },
        { id: 2, name: '100 元購物金', winProbability: 9.5 },
        { id: 3, name: '免運券', winProbability: 30.0 },
      ],
      noWinProbability: 59.5,
    });

    renderAt('/campaigns/1');

    expect(await screen.findByRole('heading', { name: '週年慶轉盤抽獎' })).toBeInTheDocument();
    expect(fetch.mock.calls[0]![0]).toBe('/api/v1/campaigns/1');
    expect(screen.getByTestId('campaign-time-window')).toHaveTextContent(
      // 2030-12-31T23:59:59Z 在台北時間已是 2031-01-01
      /2026年1月1日 上午8:00 ～ 2031年1月1日 清晨7:59/,
    );
    expect(screen.getAllByTestId('wheel-slice').map((s) => s.textContent)).toEqual([
      '藍牙耳機',
      '100 元購物金',
      '免運券',
      '銘謝惠顧',
    ]);
    expect(screen.getByRole('list', { name: '中獎機率' })).toHaveTextContent(/藍牙耳機\s*1%/);
    expect(screen.getByRole('list', { name: '中獎機率' })).toHaveTextContent(/銘謝惠顧\s*59.5%/);
  });

  it('shows the Problem Details title and detail when the Campaign cannot be loaded', async () => {
    stubFetch(
      404,
      { title: 'Not Found', status: 404, detail: 'Campaign 42 not found.', code: 'CAMPAIGN_NOT_FOUND' },
      'application/problem+json',
    );

    renderAt('/campaigns/42');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Not Found');
    expect(alert).toHaveTextContent('Campaign 42 not found.');
  });
});
