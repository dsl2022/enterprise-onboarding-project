import { Permission } from '../core/auth/permissions';

/**
 * Side-nav model. Each item names the capability that reveals it; the shell hides
 * anything the current principal can't do (server still enforces). A group is
 * shown only when at least one of its children is visible — so an Auditor sees
 * mostly Audit, a Read-only user sees views without action items, etc.
 */
export interface NavItem {
  label: string;
  icon: string;
  route: string;
  /** Capability required to see the item; omit for always-visible (Dashboard). */
  permission?: Permission;
}

export interface NavGroup {
  /** Optional group heading; omit for top-level standalone items. */
  label?: string;
  items: NavItem[];
}

export const NAV: NavGroup[] = [
  {
    items: [{ label: 'Dashboard', icon: 'dashboard', route: '/dashboard' }],
  },
  {
    items: [
      { label: 'Applications', icon: 'apps', route: '/applications', permission: 'app.read' },
      { label: 'Assistant', icon: 'smart_toy', route: '/assistant', permission: 'assistant.use' },
    ],
  },
  {
    label: 'Access',
    items: [
      { label: 'Catalog', icon: 'storefront', route: '/access/catalog', permission: 'catalog.read' },
      { label: 'My access', icon: 'badge', route: '/access/my-access', permission: 'myaccess.read' },
    ],
  },
  {
    items: [
      { label: 'Review queue', icon: 'fact_check', route: '/review-queue', permission: 'review.read' },
      { label: 'Teams', icon: 'groups', route: '/teams', permission: 'team.read' },
      { label: 'Audit', icon: 'receipt_long', route: '/audit', permission: 'audit.read' },
    ],
  },
];
