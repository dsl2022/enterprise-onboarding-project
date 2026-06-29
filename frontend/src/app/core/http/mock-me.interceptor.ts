import { HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { of, throwError } from 'rxjs';
import { API_BASE } from '../api/api.config';
import { ImpersonationRequest, Me, Role } from '../api/models';
import { ProblemError } from './problem';

/**
 * DEV-ONLY mock for identity. Lets you browse the shell + exercise role-aware UI
 * and impersonation without standing up the BFF + Entra. Registered only when
 * `environment.useMockMe` is true, so it tree-shakes out of prod builds entirely.
 *
 * Runtime controls (localStorage, no rebuild):
 *   eop.mockRole         the "logged-in" role           (default SUPER_ADMIN)
 *   eop.mockImpersonate  role to view as (super only)   (cleared by exit)
 *
 * It short-circuits exactly the three identity calls; everything else falls
 * through to the real interceptor chain (which, in dev, hits the proxy).
 */
const PRECEDENCE: Role[] = [
  'SUPER_ADMIN',
  'ADMIN',
  'SSO_OPERATIONS',
  'AUDITOR',
  'APPLICATION_OWNER',
  'READ_ONLY',
];

export const mockMeInterceptor: HttpInterceptorFn = (req, next) => {
  const storage = inject(DOCUMENT).defaultView?.localStorage;
  const path = req.url.split('?')[0];

  if (path === `${API_BASE}/me` && req.method === 'GET') {
    // Start signed-out (like a real first visit) until the dev login sets the flag;
    // a 401 here mirrors the real BFF and routes to the login screen.
    if (storage?.getItem('eop.mockSignedIn') !== 'true') {
      return throwError(
        () => new ProblemError(401, { status: 401, title: 'Unauthorized', detail: 'Mock: signed out' }),
      );
    }
    return of(new HttpResponse({ status: 200, body: buildMe(storage) }));
  }

  if (path === `${API_BASE}/impersonation` && req.method === 'POST') {
    const role = (req.body as ImpersonationRequest | null)?.role;
    if (role) {
      storage?.setItem('eop.mockImpersonate', role);
    }
    return of(new HttpResponse({ status: 200, body: buildMe(storage) }));
  }

  if (path === `${API_BASE}/impersonation` && req.method === 'DELETE') {
    storage?.removeItem('eop.mockImpersonate');
    return of(new HttpResponse({ status: 204 }));
  }

  return next(req);
};

function buildMe(storage: Storage | undefined): Me {
  const realRole = (storage?.getItem('eop.mockRole') as Role) || 'SUPER_ADMIN';
  const isSuperAdmin = realRole === 'SUPER_ADMIN';
  const impersonated = isSuperAdmin ? (storage?.getItem('eop.mockImpersonate') as Role | null) : null;

  const effectiveRoles: Role[] = impersonated ? [impersonated] : [realRole];
  const displayRole = [...effectiveRoles].sort(
    (a, b) => PRECEDENCE.indexOf(a) - PRECEDENCE.indexOf(b),
  )[0];

  return {
    id: 'mock-user',
    name: 'Dev User',
    email: 'dev@corp.example',
    role: displayRole,
    roles: effectiveRoles,
    group: null,
    isSuperAdmin,
    impersonating: impersonated ? { role: impersonated } : null,
  };
}
