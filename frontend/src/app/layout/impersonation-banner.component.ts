import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { AuthService } from '../core/auth/auth.service';
import { roleLabel } from '../shared/role-label';

/**
 * Persistent, unmistakable banner shown while Super Admin is impersonating a role,
 * with a one-click exit back to Super Admin. Hidden otherwise. The privileged
 * accent color marks this as a special mode.
 */
@Component({
  selector: 'app-impersonation-banner',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule],
  template: `
    @if (auth.isImpersonating()) {
      <div class="banner" role="status">
        <mat-icon aria-hidden="true">visibility</mat-icon>
        <span class="text">
          Viewing as <strong>{{ label() }}</strong> — actions are still audited as Super Admin.
        </span>
        <button mat-flat-button class="exit" [disabled]="pending" (click)="exit()">
          Exit impersonation
        </button>
      </div>
    }
  `,
  styles: [
    `
      .banner {
        display: flex;
        align-items: center;
        gap: var(--space-3);
        padding: var(--space-2) var(--space-4);
        background: var(--god-mode-banner);
        color: #fff;
        font-size: 14px;
      }
      .text {
        flex: 1 1 auto;
      }
      .exit {
        --mdc-filled-button-container-color: rgba(255, 255, 255, 0.16);
        --mdc-filled-button-label-text-color: #fff;
      }
    `,
  ],
})
export class ImpersonationBannerComponent {
  protected readonly auth = inject(AuthService);
  protected pending = false;

  protected label(): string {
    const role = this.auth.impersonatedRole();
    return role ? roleLabel(role) : '';
  }

  protected async exit(): Promise<void> {
    this.pending = true;
    try {
      await this.auth.stopImpersonation();
    } finally {
      this.pending = false;
    }
  }
}
