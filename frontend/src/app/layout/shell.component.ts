import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { BreakpointObserver, Breakpoints } from '@angular/cdk/layout';
import { map } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatDividerModule } from '@angular/material/divider';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthService } from '../core/auth/auth.service';
import { ThemeService } from '../core/theme/theme.service';
import { roleLabel } from '../shared/role-label';
import { NAV, NavGroup } from './nav';
import { NotificationBellComponent } from './notification-bell.component';
import { ImpersonationControlComponent } from './impersonation-control.component';
import { ImpersonationBannerComponent } from './impersonation-banner.component';

/**
 * The application shell: top bar (brand, notification bell, impersonation control
 * for Super Admin, theme toggle, user menu), a role-aware side nav, the
 * impersonation banner, and the routed content. Collapses to an over-mode drawer
 * on narrow widths (internal tooling is desktop-first).
 */
@Component({
  selector: 'app-shell',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterLink,
    RouterLinkActive,
    RouterOutlet,
    MatButtonModule,
    MatIconModule,
    MatListModule,
    MatMenuModule,
    MatSidenavModule,
    MatToolbarModule,
    MatDividerModule,
    MatTooltipModule,
    NotificationBellComponent,
    ImpersonationControlComponent,
    ImpersonationBannerComponent,
  ],
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
})
export class ShellComponent {
  protected readonly auth = inject(AuthService);
  protected readonly theme = inject(ThemeService);

  private readonly isHandset = toSignal(
    inject(BreakpointObserver)
      .observe([Breakpoints.XSmall, Breakpoints.Small])
      .pipe(map((r) => r.matches)),
    { initialValue: false },
  );

  protected readonly handset = this.isHandset;
  protected readonly sidenavMode = computed<'over' | 'side'>(() =>
    this.isHandset() ? 'over' : 'side',
  );

  /** Nav groups with items the principal can see; empty groups dropped. */
  protected readonly visibleNav = computed<NavGroup[]>(() => {
    const perms = this.auth.permissions();
    return NAV.map((group) => ({
      ...group,
      items: group.items.filter((item) => !item.permission || perms.has(item.permission)),
    })).filter((group) => group.items.length > 0);
  });

  protected readonly displayRoleLabel = computed(() => {
    const role = this.auth.displayRole();
    return role ? roleLabel(role) : '';
  });

  protected readonly initials = computed(() => {
    const name = this.auth.me()?.name ?? '';
    return name
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((p) => p[0]?.toUpperCase())
      .join('');
  });
}
