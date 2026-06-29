/** Dev defaults. Mock /me ON so the shell is browsable without the BFF + Entra. */
export const environment = {
  production: false,
  /**
   * Fakes GET /me and POST/DELETE /impersonation locally (see mock-me.interceptor).
   * Toggle the impersonated/real role at runtime via localStorage:
   *   localStorage['eop.mockRole']        — the "logged-in" role (default SUPER_ADMIN)
   *   localStorage['eop.mockImpersonate']  — role to view as (only if real = SUPER_ADMIN)
   * Set useMockMe = false here to point dev at a real BFF instead.
   */
  useMockMe: true,
};
