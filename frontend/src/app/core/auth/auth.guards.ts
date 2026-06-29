import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { Permission } from './permissions';

/**
 * Gate that requires a signed-in session. Resolves the one-time `/me` probe; if
 * there's no session it sends the user to the login screen (which starts the BFF
 * → Entra redirect in prod, or the dev sign-in when mocking).
 */
export const authGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const me = await auth.ensureLoaded();
  return me ? true : router.parseUrl('/login');
};

/**
 * Route-level capability gate. UX-only (the server still enforces) — a user who
 * lacks the permission is sent to the dashboard rather than shown an empty page.
 */
export function permissionGuard(permission: Permission): CanActivateFn {
  return async () => {
    const auth = inject(AuthService);
    const router = inject(Router);
    const me = await auth.ensureLoaded();
    if (!me) {
      return router.parseUrl('/login');
    }
    return auth.can(permission) ? true : router.parseUrl('/dashboard');
  };
}
