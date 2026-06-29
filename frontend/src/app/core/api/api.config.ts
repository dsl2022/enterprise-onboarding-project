/** Network constants. Same-origin behind the BFF (dev proxies these to :8080). */

/** All v1 REST endpoints hang off this base (see openapi-v1.yaml `servers`). */
export const API_BASE = '/api/v1';

/** BFF login entry — full-page redirect to Entra, NOT an XHR. */
export const LOGIN_URL = '/auth/login';

/** RP-initiated logout (clears session + signs out of Entra). */
export const LOGOUT_URL = '/auth/logout';
