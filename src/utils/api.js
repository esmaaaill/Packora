/**
 * Shared API utility — automatically attaches the JWT Bearer token
 * from localStorage to every outgoing request.
 *
 * Usage:
 *   import { apiFetch, API_BASE } from '../utils/api';
 *
 *   // GET
 *   const data = await apiFetch('/api/orders/me');
 *
 *   // POST
 *   const data = await apiFetch('/api/orders', {
 *     method: 'POST',
 *     body: JSON.stringify(payload),
 *   });
 */

export const API_BASE = 'http://localhost:8080';
const AUTH_STORAGE_KEY = 'packora_user_auth';

/**
 * Returns the JWT token from localStorage, or null.
 */
export function getStoredToken() {
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    return parsed?.token || null;
  } catch {
    return null;
  }
}

/**
 * Wrapper around fetch() that:
 *  1. Prepends API_BASE if the url starts with "/"
 *  2. Sets Content-Type to application/json (unless overridden or body is FormData)
 *  3. Attaches Authorization: Bearer <token> if a token exists
 *  4. Parses JSON response automatically
 *  5. On 401, clears stored auth and redirects to /login
 *
 * Returns the parsed JSON body.
 * Throws an error with `status` and `data` properties on non-2xx responses.
 */
export async function apiFetch(url, options = {}) {
  const fullUrl = url.startsWith('http') ? url : `${API_BASE}${url}`;

  const headers = { ...(options.headers || {}) };

  // Auto-set JSON content-type unless body is FormData
  if (!(options.body instanceof FormData) && !headers['Content-Type']) {
    headers['Content-Type'] = 'application/json';
  }

  // Attach JWT token
  const token = getStoredToken();
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const response = await fetch(fullUrl, {
    ...options,
    headers,
  });

  // Handle 401 — token expired or invalid
  if (response.status === 401) {
    localStorage.removeItem(AUTH_STORAGE_KEY);
    // Only redirect if we're in a browser context and not already on login
    if (typeof window !== 'undefined' && !window.location.pathname.includes('/login')) {
      window.location.href = '/login';
    }
    const err = new Error('Unauthorized');
    err.status = 401;
    throw err;
  }

  // Try to parse JSON (some responses may be empty, e.g. 204)
  let data = null;
  const contentType = response.headers.get('content-type');
  if (contentType && contentType.includes('application/json')) {
    data = await response.json();
  }

  if (!response.ok) {
    const err = new Error(data?.message || `Request failed with status ${response.status}`);
    err.status = response.status;
    err.data = data;
    throw err;
  }

  return data;
}
