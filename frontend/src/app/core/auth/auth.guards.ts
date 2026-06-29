import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { Permission } from './permissions';

/**
 * Gate that requires a signed-in session. Resolves the one-time `/me` probe; if
 * there's no session it kicks off the BFF login redirect and blocks the route.
 */
export const authGuard: CanActivateFn = async () => {
  const auth = inject(AuthService);
  const me = await auth.ensureLoaded();
  if (me) {
    return true;
  }
  auth.login();
  return false;
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
      auth.login();
      return false;
    }
    return auth.can(permission) ? true : router.parseUrl('/dashboard');
  };
}
