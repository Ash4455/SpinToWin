import { apiErrorFrom, networkError } from './problem';

export { ApiError } from './problem';
export type { ProblemDetail } from './problem';

export interface ApiClientOptions {
  /** 例：'/api/v1'（同源，經 Nginx 或 Vite dev server 代理到 gateway） */
  baseUrl: string;
  /** 回傳目前有效的 access token；未登入時回傳 undefined */
  getToken: () => Promise<string | undefined>;
  /** API 回 401（token 失效）時呼叫，通常是導回 Keycloak 登入 */
  onUnauthorized?: () => void;
  fetch?: typeof fetch;
}

export interface RequestOptions {
  headers?: HeadersInit;
  signal?: AbortSignal;
}

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

/**
 * 呼叫 gateway API 的薄封裝：
 * - 自動帶 `Authorization: Bearer <token>`
 * - JSON request／response
 * - 非 2xx 一律拋出 ApiError（Problem Details 的 title／detail／code），網路失敗為 status 0
 */
export function createApiClient({ baseUrl, getToken, onUnauthorized, fetch: fetchImpl }: ApiClientOptions) {
  async function request<T>(method: HttpMethod, path: string, body?: unknown, options: RequestOptions = {}): Promise<T> {
    const doFetch = fetchImpl ?? globalThis.fetch.bind(globalThis);
    const headers = new Headers(options.headers);
    headers.set('Accept', 'application/json, application/problem+json');
    if (body !== undefined) headers.set('Content-Type', 'application/json');
    const token = await getToken();
    if (token) headers.set('Authorization', `Bearer ${token}`);

    let response: Response;
    try {
      response = await doFetch(`${baseUrl}${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: options.signal,
      });
    } catch (e) {
      if (e instanceof DOMException && e.name === 'AbortError') throw e;
      throw networkError(e);
    }

    if (!response.ok) {
      if (response.status === 401) onUnauthorized?.();
      throw await apiErrorFrom(response);
    }
    const text = await response.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }

  return {
    request,
    get: <T>(path: string, options?: RequestOptions) => request<T>('GET', path, undefined, options),
    post: <T>(path: string, body?: unknown, options?: RequestOptions) => request<T>('POST', path, body, options),
    put: <T>(path: string, body?: unknown, options?: RequestOptions) => request<T>('PUT', path, body, options),
    patch: <T>(path: string, body?: unknown, options?: RequestOptions) => request<T>('PATCH', path, body, options),
    delete: <T = void>(path: string, options?: RequestOptions) => request<T>('DELETE', path, undefined, options),
  };
}

export type ApiClient = ReturnType<typeof createApiClient>;
