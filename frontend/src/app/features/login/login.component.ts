import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { AuthService } from '../../core/auth/auth.service';
import { Role, ROLES } from '../../core/api/models';
import { roleLabel } from '../../shared/role-label';
import { environment } from '../../../environments/environment';

/**
 * Login landing. In prod it's the entry to the BFF → Entra sign-in (a full-page
 * redirect; the browser never handles tokens). In dev (`useMockMe`) it's a
 * simulated sign-in with a role picker so the whole login/logout UX is
 * demonstrable without a backend.
 */
@Component({
  selector: 'app-login',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatSelectModule],
  template: `
    <div class="login-page">
      <div class="card card-surface">
        <div class="brand">
          <mat-icon class="brand-mark">shield_lock</mat-icon>
          <span class="brand-name">SSO Onboarding Portal</span>
        </div>
        <p class="lede text-muted">Sign in to continue.</p>

        @if (isMock) {
          <div class="dev-note">Dev mode — simulated sign-in (no Microsoft account needed).</div>
          <mat-form-field appearance="outline" class="role-field">
            <mat-label>Sign in as</mat-label>
            <mat-select [(ngModel)]="selectedRole">
              @for (role of roles; track role) {
                <mat-option [value]="role">{{ label(role) }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
        }

        <button mat-flat-button color="primary" class="signin" (click)="signIn()">
          <mat-icon>login</mat-icon>
          {{ isMock ? 'Sign in (dev)' : 'Sign in with Microsoft' }}
        </button>
      </div>
    </div>
  `,
  styles: [
    `
      .login-page {
        min-height: 100%;
        display: flex;
        align-items: center;
        justify-content: center;
        padding: var(--space-5);
        background: var(--bg);
      }
      .card {
        width: 100%;
        max-width: 380px;
        padding: var(--space-6);
        display: flex;
        flex-direction: column;
        gap: var(--space-3);
      }
      .brand {
        display: flex;
        align-items: center;
        gap: var(--space-2);
      }
      .brand-mark {
        color: var(--primary);
      }
      .brand-name {
        font-weight: 600;
        font-size: 18px;
      }
      .lede {
        margin: 0 0 var(--space-2);
        font-size: 14px;
      }
      .dev-note {
        font-size: 12px;
        color: var(--god-mode-fg);
        background: var(--god-mode-bg);
        padding: var(--space-2) var(--space-3);
        border-radius: var(--radius-md);
      }
      .role-field {
        width: 100%;
      }
      .signin {
        height: 44px;
      }
    `,
  ],
})
export class LoginComponent implements OnInit {
  protected readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly isMock = environment.useMockMe;
  protected readonly roles: readonly Role[] = ROLES;
  protected selectedRole: Role = 'SUPER_ADMIN';

  ngOnInit(): void {
    // Already signed in (e.g. landed here by accident) → go to the app.
    if (this.auth.isAuthenticated()) {
      void this.router.navigateByUrl('/dashboard');
    }
  }

  protected label(role: Role): string {
    return roleLabel(role);
  }

  protected signIn(): void {
    if (this.isMock) {
      void this.auth.mockSignIn(this.selectedRole);
    } else {
      this.auth.login();
    }
  }
}
