import { Routes } from '@angular/router';
import { authGuard, permissionGuard } from './core/auth/auth.guards';

/**
 * Routes mirror the side nav. Every route requires a session (authGuard); feature
 * routes additionally require the capability that reveals them (permissionGuard) —
 * UX-only, the server still enforces. Feature screens are placeholders for now;
 * the `data.status` badge tells the truth about backend readiness (STATUS.md).
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },

  {
    path: 'login',
    loadComponent: () => import('./features/login/login.component').then((m) => m.LoginComponent),
  },

  {
    path: 'dashboard',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/dashboard/dashboard.component').then((m) => m.DashboardComponent),
  },

  {
    path: 'applications',
    canActivate: [authGuard, permissionGuard('app.read')],
    loadComponent: () =>
      import('./features/applications/applications-list.component').then(
        (m) => m.ApplicationsListComponent,
      ),
  },
  {
    // Must precede ':id' so "new" isn't swallowed as an id.
    path: 'applications/new',
    canActivate: [authGuard, permissionGuard('app.create')],
    loadComponent: () =>
      import('./features/applications/application-create.component').then(
        (m) => m.ApplicationCreateComponent,
      ),
  },
  {
    path: 'applications/:id',
    canActivate: [authGuard, permissionGuard('app.read')],
    loadComponent: () =>
      import('./features/applications/application-detail.component').then(
        (m) => m.ApplicationDetailComponent,
      ),
  },

  {
    path: 'access/catalog',
    canActivate: [authGuard, permissionGuard('catalog.read')],
    loadComponent: () =>
      import('./features/access/catalog.component').then((m) => m.CatalogComponent),
  },
  {
    path: 'access/my-access',
    canActivate: [authGuard, permissionGuard('myaccess.read')],
    loadComponent: () =>
      import('./features/access/my-access.component').then((m) => m.MyAccessComponent),
  },

  {
    path: 'review-queue',
    canActivate: [authGuard, permissionGuard('review.read')],
    loadComponent: () =>
      import('./features/review/review-queue.component').then((m) => m.ReviewQueueComponent),
  },

  {
    path: 'teams',
    canActivate: [authGuard, permissionGuard('team.read')],
    loadComponent: () =>
      import('./features/teams/teams-list.component').then((m) => m.TeamsListComponent),
  },
  {
    path: 'teams/:id',
    canActivate: [authGuard, permissionGuard('team.read')],
    loadComponent: () =>
      import('./features/teams/team-detail.component').then((m) => m.TeamDetailComponent),
  },

  {
    path: 'audit',
    canActivate: [authGuard, permissionGuard('audit.read')],
    loadComponent: () => import('./features/audit/audit.component').then((m) => m.AuditComponent),
  },

  { path: '**', redirectTo: 'dashboard' },
];
