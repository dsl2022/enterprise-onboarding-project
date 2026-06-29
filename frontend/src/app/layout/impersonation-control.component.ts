import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { AuthService } from '../core/auth/auth.service';
import { Role, ROLES } from '../core/api/models';
import { roleLabel } from '../shared/role-label';

/**
 * Audited "View as role" control — rendered ONLY for Super Admin (god mode).
 * Regular users get no role switcher (their role is fixed by login). Picking a
 * role calls POST /impersonation; the server still attributes every action to the
 * real Super Admin (SoD/ABAC/audit), so this only changes the *view*.
 */
@Component({
  selector: 'app-impersonation-control',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule, MatMenuModule],
  template: `
    <button mat-stroked-button class="god-trigger" [matMenuTriggerFor]="menu">
      <mat-icon>visibility</mat-icon>
      View as
      <mat-icon iconPositionEnd>arrow_drop_down</mat-icon>
    </button>

    <mat-menu #menu="matMenu" xPosition="before">
      <div class="menu-caption" (click)="$event.stopPropagation()">View the portal as…</div>
      @for (role of impersonatableRoles; track role) {
        <button
          mat-menu-item
          [disabled]="pending"
          (click)="impersonate(role)"
        >
          <mat-icon>{{ auth.impersonatedRole() === role ? 'check' : 'badge' }}</mat-icon>
          {{ label(role) }}
        </button>
      }
    </mat-menu>
  `,
  styles: [
    `
      .god-trigger {
        --mdc-outlined-button-label-text-color: var(--god-mode-fg);
        --mdc-outlined-button-outline-color: var(--god-mode-fg);
      }
      .menu-caption {
        padding: var(--space-2) var(--space-4);
        font-size: 12px;
        color: var(--text-subtle);
      }
    `,
  ],
})
export class ImpersonationControlComponent {
  protected readonly auth = inject(AuthService);
  protected pending = false;

  /** Impersonating Super Admin is meaningless — offer the other five roles. */
  protected readonly impersonatableRoles: Role[] = ROLES.filter((r) => r !== 'SUPER_ADMIN');

  protected label(role: Role): string {
    return roleLabel(role);
  }

  protected async impersonate(role: Role): Promise<void> {
    this.pending = true;
    try {
      await this.auth.impersonate(role);
    } finally {
      this.pending = false;
    }
  }
}
