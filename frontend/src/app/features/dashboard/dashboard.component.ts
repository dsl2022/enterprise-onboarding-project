import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { AuthService } from '../../core/auth/auth.service';
import { Permission } from '../../core/auth/permissions';
import { PageHeaderComponent } from '../../shared/components/page-header.component';
import { roleLabel } from '../../shared/role-label';

interface QuickLink {
  label: string;
  description: string;
  icon: string;
  route: string;
  permission?: Permission;
}

const QUICK_LINKS: QuickLink[] = [
  {
    label: 'Onboard an application',
    description: 'Register an internal app for SSO and track it through approval.',
    icon: 'apps',
    route: '/applications',
    permission: 'app.create',
  },
  {
    label: 'Request access',
    description: 'Browse the catalog and request a resource, role, or team.',
    icon: 'storefront',
    route: '/access/catalog',
    permission: 'access.request',
  },
  {
    label: 'Review queue',
    description: 'Approve or reject onboarding and access requests awaiting you.',
    icon: 'fact_check',
    route: '/review-queue',
    permission: 'review.read',
  },
  {
    label: 'My access',
    description: 'See what you currently hold and request removal.',
    icon: 'badge',
    route: '/access/my-access',
    permission: 'myaccess.read',
  },
  {
    label: 'Audit log',
    description: 'Inspect the tamper-evident, hash-chained activity trail.',
    icon: 'receipt_long',
    route: '/audit',
    permission: 'audit.read',
  },
];

/**
 * Landing page. Greets the signed-in principal and surfaces only the actions
 * their capabilities allow — a Read-only user or Auditor sees a reduced set,
 * matching the role-aware nav.
 */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, MatIconModule, PageHeaderComponent],
  template: `
    <div class="page">
      <app-page-header [title]="greeting()" [subtitle]="subtitle()" />

      <div class="grid">
        @for (link of visibleLinks(); track link.route) {
          <a class="card-surface tile" [routerLink]="link.route">
            <mat-icon class="tile-icon">{{ link.icon }}</mat-icon>
            <span class="tile-title">{{ link.label }}</span>
            <span class="tile-desc text-muted">{{ link.description }}</span>
          </a>
        }
      </div>
    </div>
  `,
  styles: [
    `
      .grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
        gap: var(--space-4);
      }
      .tile {
        display: flex;
        flex-direction: column;
        gap: var(--space-2);
        padding: var(--space-5);
        text-decoration: none;
        color: var(--text);
        transition: border-color 0.15s ease, transform 0.15s ease;
      }
      .tile:hover {
        text-decoration: none;
        border-color: var(--primary);
        transform: translateY(-1px);
      }
      .tile-icon {
        color: var(--primary);
        width: 28px;
        height: 28px;
        font-size: 28px;
      }
      .tile-title {
        font-weight: 600;
        font-size: 15px;
      }
      .tile-desc {
        font-size: 13px;
        line-height: 1.4;
      }
    `,
  ],
})
export class DashboardComponent {
  private readonly auth = inject(AuthService);

  protected readonly greeting = computed(() => {
    const name = this.auth.me()?.name?.split(/\s+/)[0] ?? 'there';
    return `Welcome, ${name}`;
  });

  protected readonly subtitle = computed(() => {
    const role = this.auth.displayRole();
    return role ? `You're signed in as ${roleLabel(role)}.` : '';
  });

  protected readonly visibleLinks = computed(() =>
    QUICK_LINKS.filter((l) => !l.permission || this.auth.can(l.permission)),
  );
}
