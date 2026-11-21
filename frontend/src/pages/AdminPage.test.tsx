import { fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import { AdminPage } from './AdminPage';

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': status >= 400 ? 'application/problem+json' : 'application/json' },
  });
}

const draft = {
  id: 7,
  name: '雙十一轉盤',
  startAt: '2026-12-01T02:00:00Z',
  endAt: '2026-12-31T15:59:00Z',
  drawQuota: 3,
  status: 'DRAFT',
  prizes: [],
  noWinProbability: 100,
};

function renderAdmin(fetch: ReturnType<typeof vi.fn>) {
  vi.stubGlobal('fetch', fetch);
  render(
    <MemoryRouter initialEntries={['/admin']}>
      <Routes>
        <Route path="/admin" element={<AdminPage />} />
        <Route path="/admin/campaigns/:campaignId" element={<p>campaign page</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

async function fillCampaignForm() {
  await userEvent.type(screen.getByLabelText('名稱'), '雙十一轉盤');
  fireEvent.change(screen.getByLabelText('開始時間'), { target: { value: '2026-12-01T10:00' } });
  fireEvent.change(screen.getByLabelText('結束時間'), { target: { value: '2026-12-31T23:59' } });
  await userEvent.type(screen.getByLabelText('Draw Quota（每人抽獎次數上限）'), '3');
}

describe('AdminPage', () => {
  it('lists every Campaign with its Campaign Status and a link to manage it', async () => {
    renderAdmin(vi.fn().mockResolvedValue(json(200, [{ ...draft, id: 1, name: '週年慶轉盤抽獎', status: 'ACTIVE' }, draft])));

    const table = await screen.findByRole('table', { name: 'Campaign 列表' });
    const rows = within(table).getAllByRole('row').slice(1);
    expect(rows.map((r) => r.textContent)).toEqual([
      expect.stringMatching(/週年慶轉盤抽獎.*ACTIVE/),
      expect.stringMatching(/雙十一轉盤.*DRAFT/),
    ]);
    expect(within(rows[1]!).getByRole('link', { name: '雙十一轉盤' })).toHaveAttribute('href', '/admin/campaigns/7');
  });

  it('creates a DRAFT Campaign with the local time window sent as UTC, then opens it', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(json(200, [])).mockResolvedValueOnce(json(201, draft));
    renderAdmin(fetch);
    await screen.findByText('尚無 Campaign。');

    await fillCampaignForm();
    await userEvent.click(screen.getByRole('button', { name: '建立 Campaign' }));

    expect(await screen.findByText('campaign page')).toBeInTheDocument();
    const [url, init] = fetch.mock.calls[1]!;
    expect(url).toBe('/api/v1/admin/campaigns');
    expect(init.method).toBe('POST');
    // 台北時間（UTC+8）
    expect(JSON.parse(init.body)).toEqual({
      name: '雙十一轉盤',
      startAt: '2026-12-01T02:00:00.000Z',
      endAt: '2026-12-31T15:59:00.000Z',
      drawQuota: 3,
    });
  });

  it('shows the backend field errors next to the fields they belong to', async () => {
    const fetch = vi
      .fn()
      .mockResolvedValueOnce(json(200, []))
      .mockResolvedValueOnce(
        json(400, {
          title: 'Bad Request',
          status: 400,
          code: 'VALIDATION_FAILED',
          detail: 'Request validation failed.',
          errors: [
            { field: 'endAt', message: 'must be after startAt' },
            { field: 'drawQuota', message: 'must be greater than 0' },
          ],
        }),
      );
    renderAdmin(fetch);
    await screen.findByText('尚無 Campaign。');

    await fillCampaignForm();
    await userEvent.click(screen.getByRole('button', { name: '建立 Campaign' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('輸入資料有誤，請修正標示的欄位');
    expect(screen.getByLabelText('結束時間')).toHaveAccessibleDescription('must be after startAt');
    expect(screen.getByLabelText('結束時間')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Draw Quota（每人抽獎次數上限）')).toHaveAccessibleDescription('must be greater than 0');
    expect(screen.getByLabelText('名稱')).not.toHaveAttribute('aria-invalid');
  });
});
