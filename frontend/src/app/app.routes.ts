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
      import('./features/placeholder/placeholder.component').then((m) => m.PlaceholderComponent),
    data: {
      title: 'Applications',
      subtitle: 'Register internal apps for SSO and track onboarding through approval.',
      status: 'live',
      note: 'The onboarding list, wizard, and detail timeline build on the live /applications endpoints next.',
    },
  },

  {
    path: 'access/catalog',
    canActivate: [authGuard, permissionGuard('catalog.read')],
    loadComponent: () =>
      import('./features/placeholder/placeholder.component').then((m) => m.PlaceholderComponent),
    data: {
      title: 'Access catalog',
      subtitle: 'Browse requestable resources, roles, and teams — then request access.',
      status: 'live',
      note: 'The catalog grid and request-access flow build on the live /catalog and /access-requests endpoints.',
    },
  },

  {
    path: 'access/my-access',
    canActivate: [authGuard, permissionGuard('myaccess.read')],
    loadComponent: () =>
      import('./features/placeholder/placeholder.component').then((m) => m.PlaceholderComponent),
    data: {
      title: 'My access',
      subtitle: 'What you currently hold, and how to request removal.',
      status: 'live',
      note: '/my-access is the source of truth for currently-held resources.',
    },
  },

  {
    path: 'review-queue',
    canActivate: [authGuard, permissionGuard('review.read')],
    loadComponent: () =>
      import('./features/placeholder/placeholder.component').then((m) => m.PlaceholderComponent),
    data: {
      title: 'Review queue',
      subtitle: 'One queue for both onboarding and access requests awaiting a decision.',
      status: 'live',
      note: 'Approve/reject with separation-of-duties on the live /review-queue and decision endpoints.',
    },
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
