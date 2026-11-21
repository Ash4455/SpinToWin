import { describe, expect, it, vi } from 'vitest';
import { ApiError, createApiClient } from './client';

function jsonResponse(body: unknown, status = 200, contentType = 'application/json'): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } });
}

describe('api client', () => {
  it('calls the API under the base URL with the Bearer token and returns the JSON body', async () => {
    const fetch = vi.fn().mockResolvedValue(jsonResponse({ id: 1, name: '週年慶轉盤抽獎' }));
    const client = createApiClient({ baseUrl: '/api/v1', getToken: async () => 'tkn', fetch });

    const body = await client.get('/campaigns/1');

    expect(body).toEqual({ id: 1, name: '週年慶轉盤抽獎' });
    const [url, init] = fetch.mock.calls[0]!;
    expect(url).toBe('/api/v1/campaigns/1');
    expect(new Headers(init.headers).get('Authorization')).toBe('Bearer tkn');
  });

  it('turns a Problem Details response into an ApiError carrying its title, detail and code', async () => {
    const problem = {
      type: 'about:blank',
      title: 'Not Found',
      status: 404,
      detail: 'Campaign 42 not found.',
      instance: '/campaigns/42',
      code: 'CAMPAIGN_NOT_FOUND',
    };
    const fetch = vi.fn().mockResolvedValue(jsonResponse(problem, 404, 'application/problem+json'));
    const client = createApiClient({ baseUrl: '/api/v1', getToken: async () => 'tkn', fetch });

    const error = await client.get('/campaigns/42').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      status: 404,
      title: 'Not Found',
      detail: 'Campaign 42 not found.',
      code: 'CAMPAIGN_NOT_FOUND',
      problem,
    });
  });

  it('still reports the HTTP status when an error response is not Problem Details', async () => {
    // 例如 gateway 掛掉時 Nginx 回的 HTML 502
    const fetch = vi.fn().mockResolvedValue(
      new Response('<html>Bad Gateway</html>', { status: 502, statusText: 'Bad Gateway', headers: { 'Content-Type': 'text/html' } }),
    );
    const client = createApiClient({ baseUrl: '/api/v1', getToken: async () => 'tkn', fetch });

    const error = await client.get('/campaigns/1').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 502, title: 'Bad Gateway' });
  });

  it('reports a network failure as an ApiError with status 0', async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'));
    const client = createApiClient({ baseUrl: '/api/v1', getToken: async () => 'tkn', fetch });

    const error = await client.get('/campaigns/1').catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 0, title: '無法連線到伺服器' });
  });

  it('sends a JSON body with extra headers', async () => {
    const fetch = vi.fn().mockResolvedValue(jsonResponse({ ok: true }, 201));
    const client = createApiClient({ baseUrl: '/api/v1', getToken: async () => 'tkn', fetch });

    await client.post('/campaigns/1/draws', { count: 3 }, { headers: { 'Idempotency-Key': 'k-1' } });

    const [url, init] = fetch.mock.calls[0]!;
    const headers = new Headers(init.headers);
    expect(url).toBe('/api/v1/campaigns/1/draws');
    expect(init.method).toBe('POST');
    expect(init.body).toBe('{"count":3}');
    expect(headers.get('Content-Type')).toBe('application/json');
    expect(headers.get('Idempotency-Key')).toBe('k-1');
  });

  it('resolves to undefined for 204 No Content', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    const client = createApiClient({ baseUrl: '/api/v1', getToken: async () => 'tkn', fetch });

    await expect(client.delete('/admin/campaigns/9')).resolves.toBeUndefined();
  });

  it('tells the auth layer when the API answers 401', async () => {
    const onUnauthorized = vi.fn();
    const fetch = vi
      .fn()
      .mockResolvedValue(jsonResponse({ title: 'Unauthorized', status: 401, code: 'UNAUTHORIZED' }, 401, 'application/problem+json'));
    const client = createApiClient({ baseUrl: '/api/v1', getToken: async () => 'expired', fetch, onUnauthorized });

    await expect(client.get('/campaigns/1')).rejects.toMatchObject({ status: 401, code: 'UNAUTHORIZED' });
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });
});
