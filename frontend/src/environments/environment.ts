/** Production defaults. Real BFF only — no mocks. */
export const environment = {
  production: true,
  /** When true, a dev-only interceptor fakes GET /me + /impersonation. NEVER in prod. */
  useMockMe: false,
};
