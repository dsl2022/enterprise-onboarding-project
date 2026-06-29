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
      import('./features/placeholder/placeholder.component').then((m) => m.PlaceholderComponent),
    data: {
      title: 'Teams',
      subtitle: 'Teams and their members.',
      status: 'mock',
      note: 'Teams (Phase 5c) is not built on the backend yet — this screen will run on mock data until it lands.',
    },
  },

  {
    path: 'audit',
    canActivate: [authGuard, permissionGuard('audit.read')],
    loadComponent: () =>
      import('./features/placeholder/placeholder.component').then((m) => m.PlaceholderComponent),
    data: {
      title: 'Audit log',
      subtitle: 'Tamper-evident, hash-chained activity trail.',
      status: 'mock',
      note: 'Audit (Phase 6) is not built on the backend yet — this screen will run on mock data until it lands.',
    },
  },

  { path: '**', redirectTo: 'dashboard' },
];
