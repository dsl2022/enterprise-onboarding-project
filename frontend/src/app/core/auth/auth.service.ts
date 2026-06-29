import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { API_BASE, LOGIN_URL, LOGOUT_URL } from '../api/api.config';
import { ImpersonationRequest, Me, Role } from '../api/models';
import { ProblemError } from '../http/problem';
import { Permission, permissionsFor } from './permissions';

/**
 * The identity hub. Calls `GET /me` once on startup, then exposes the principal
 * and derived capabilities as signals the whole app reads from.
 *
 * Auth model recap (BFF): the browser holds no tokens — only the session cookie.
 * A 401 on the `/me` probe means "not signed in"; `login()` is a full-page
 * redirect to the BFF. While impersonating, `/me.roles` already reflects the
 * REDUCED (impersonated) role, so `can()` gates to that view automatically, while
 * `isSuperAdmin` stays true so the impersonation banner/control remain visible.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly doc = inject(DOCUMENT);

  private readonly _me = signal<Me | null>(null);
  private readonly _loaded = signal(false);

  /** Current principal, or null when not signed in. */
  readonly me = this._me.asReadonly();
  /** True once the first `/me` probe has resolved (success or 401). */
  readonly loaded = this._loaded.asReadonly();

  readonly isAuthenticated = computed(() => this._me() !== null);
  readonly displayRole = computed<Role | null>(() => this._me()?.role ?? null);
  readonly isSuperAdmin = computed(() => this._me()?.isSuperAdmin ?? false);
  readonly impersonatedRole = computed<Role | null>(() => this._me()?.impersonating?.role ?? null);
  readonly isImpersonating = computed(() => this.impersonatedRole() !== null);

  /** Effective permission set (union of held roles; reduced while impersonating). */
  readonly permissions = computed<Set<Permission>>(() => {
    const me = this._me();
    return me ? permissionsFor(me.roles) : new Set<Permission>();
  });

  private loadOnce: Promise<Me | null> | null = null;

  /** Idempotent startup probe; safe to call from guards repeatedly. */
  ensureLoaded(): Promise<Me | null> {
    return (this.loadOnce ??= this.refresh());
  }

  /** Force a re-fetch of `/me` (after impersonation changes, etc.). */
  async refresh(): Promise<Me | null> {
    try {
      const me = await firstValueFrom(this.http.get<Me>(`${API_BASE}/me`));
      this._me.set(me);
      return me;
    } catch (err) {
      if (err instanceof ProblemError && err.isUnauthorized) {
        this._me.set(null);
        return null;
      }
      throw err;
    } finally {
      this._loaded.set(true);
    }
  }

  /** Capability check for UI gating (NOT authorization — see permissions.ts). */
  can(permission: Permission): boolean {
    return this.permissions().has(permission);
  }
  canAny(...perms: Permission[]): boolean {
    return perms.some((p) => this.permissions().has(p));
  }

  // ---- Impersonation (SUPER_ADMIN only) ------------------------------------

  async impersonate(role: Role): Promise<Me> {
    const body: ImpersonationRequest = { role };
    const me = await firstValueFrom(this.http.post<Me>(`${API_BASE}/impersonation`, body));
    this._me.set(me);
    return me;
  }

  async stopImpersonation(): Promise<void> {
    await firstValueFrom(this.http.delete<void>(`${API_BASE}/impersonation`));
    await this.refresh();
  }

  // ---- Session navigation (full-page) --------------------------------------

  login(): void {
    this.doc.defaultView!.location.href = LOGIN_URL;
  }
  logout(): void {
    this.doc.defaultView!.location.href = LOGOUT_URL;
  }
}
