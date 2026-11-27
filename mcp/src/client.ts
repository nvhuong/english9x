import { config } from './config.js';

export interface RequestOptions {
  token?: string;
  query?: Record<string, string | number | boolean | undefined>;
  skipAuth?: boolean;
  timeoutMs?: number;
}

export class E9xApiClient {
  private token: string | null = null;
  private tokenExpiresAt: number = 0;

  constructor(private baseUrl: string = config.apiUrl) {
    if (config.authToken) {
      this.token = config.authToken;
      this.tokenExpiresAt = Date.now() + 365 * 24 * 3600 * 1000;
    }
  }

  /**
   * Set or update manual bearer token
   */
  setToken(token: string) {
    this.token = token;
    this.tokenExpiresAt = Date.now() + 24 * 3600 * 1000;
  }

  /**
   * Ensure we have a valid bearer token, attempting login if needed.
   */
  async ensureToken(timeoutMs: number = 3000): Promise<string | null> {
    if (this.token && Date.now() < this.tokenExpiresAt - 60000) {
      return this.token;
    }

    if (config.adminEmail && config.adminPassword) {
      try {
        const res = await this.login(config.adminEmail, config.adminPassword, timeoutMs);
        if (res && res.accessToken) {
          this.token = res.accessToken;
          this.tokenExpiresAt = Date.now() + 15 * 60 * 1000; // 15 mins default
          return this.token;
        }
      } catch (err: any) {
        console.warn(`[E9xApiClient] Auto-login warning: ${err.message}`);
      }
    }
    return this.token;
  }

  /**
   * Core request dispatcher with automatic JSON parsing, fast timeout, and error diagnostics.
   */
  async request<T = any>(
    method: 'GET' | 'POST' | 'PUT' | 'DELETE',
    path: string,
    body?: any,
    options: RequestOptions = {}
  ): Promise<{ status: number; ok: boolean; data: T }> {
    const url = new URL(path.startsWith('http') ? path : `${this.baseUrl}${path}`);
    if (options.query) {
      for (const [key, value] of Object.entries(options.query)) {
        if (value !== undefined) {
          url.searchParams.append(key, String(value));
        }
      }
    }

    const headers: Record<string, string> = {
      Accept: 'application/json',
    };

    if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }

    if (!options.skipAuth) {
      const token = options.token || (await this.ensureToken(options.timeoutMs || 2000));
      if (token) {
        headers['Authorization'] = `Bearer ${token}`;
      }
    }

    const timeout = options.timeoutMs || 4000;
    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeout);

      const response = await fetch(url.toString(), {
        method,
        headers,
        body: body !== undefined ? JSON.stringify(body) : undefined,
        signal: controller.signal,
      });
      clearTimeout(timer);

      const contentType = response.headers.get('content-type') || '';
      let data: any = null;
      if (contentType.includes('application/json')) {
        data = await response.json();
      } else {
        const text = await response.text();
        data = text ? { raw: text } : null;
      }

      if (!response.ok) {
        const errorMsg =
          data?.message || data?.error || (typeof data === 'string' ? data : `HTTP ${response.status} ${response.statusText}`);
        return {
          status: response.status,
          ok: false,
          data: { error: errorMsg, statusCode: response.status, details: data } as any,
        };
      }

      return {
        status: response.status,
        ok: true,
        data,
      };
    } catch (err: any) {
      return {
        status: 0,
        ok: false,
        data: {
          error: `Failed to connect to English9x backend at ${this.baseUrl}: ${err.message}`,
          networkError: true,
        } as any,
      };
    }
  }

  async get<T = any>(path: string, options?: RequestOptions) {
    return this.request<T>('GET', path, undefined, options);
  }

  async post<T = any>(path: string, body?: any, options?: RequestOptions) {
    return this.request<T>('POST', path, body, options);
  }

  async put<T = any>(path: string, body?: any, options?: RequestOptions) {
    return this.request<T>('PUT', path, body, options);
  }

  async delete<T = any>(path: string, options?: RequestOptions) {
    return this.request<T>('DELETE', path, undefined, options);
  }

  async login(email: string, password: string, timeoutMs: number = 3000) {
    const res = await this.request<{ accessToken: string; refreshToken: string }>(
      'POST',
      '/api/auth/login',
      { email, password },
      { skipAuth: true, timeoutMs }
    );
    if (res.ok && res.data.accessToken) {
      this.token = res.data.accessToken;
      this.tokenExpiresAt = Date.now() + 15 * 60 * 1000;
      return res.data;
    }
    throw new Error((res.data as any)?.error || 'Authentication failed');
  }

  async checkHealth(timeoutMs: number = 2000) {
    return this.get('/api/platform/ping', { skipAuth: true, timeoutMs });
  }

  async getPublicConfig(timeoutMs: number = 2000) {
    return this.get('/api/config/public', { skipAuth: true, timeoutMs });
  }

  async getMe() {
    return this.get('/api/auth/me');
  }
}

export const defaultClient = new E9xApiClient();
