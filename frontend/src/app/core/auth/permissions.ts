import { Role } from '../api/models';

/**
 * Permission catalogue + role→permission matrix, mirroring docs/api/rbac-matrix.md.
 *
 * IMPORTANT: this is for UX only — to hide actions a user can't take. The server
 * is always authoritative (it enforces the union of permissions + ABAC ownership +
 * separation of duties). Never treat a `can()` of `true` as authorization; treat a
 * 403 from the API as the real answer.
 *
 * Scope nuance (`✔(own)` in the matrix) is intentionally collapsed here: "can act
 * on my own rows" still means the button should show — the server filters/denies by
 * ownership. So this table answers "could this user ever do X?", not "on this row".
 */
export type Permission =
  | 'app.read'
  | 'app.create'
  | 'app.update'
  | 'app.submit'
  | 'app.decide'
  | 'app.provision'
  | 'catalog.read'
  | 'access.request'
  | 'access.read'
  | 'access.decide'
  | 'myaccess.read'
  | 'myaccess.removal.request'
  | 'review.read'
  | 'team.read'
  | 'team.manage'
  | 'secret.rotate'
  | 'audit.read'
  | 'notifications.read'
  | 'impersonate'
  | 'assistant.use';

const OWNER: Permission[] = [
  'app.read', 'app.create', 'app.update', 'app.submit',
  'catalog.read', 'access.request', 'access.read',
  'myaccess.read', 'myaccess.removal.request',
  'team.read', 'team.manage', 'notifications.read', 'assistant.use',
];

const SSO_OPS: Permission[] = [
  'app.read', 'app.decide', 'app.provision',
  'catalog.read', 'access.request', 'access.read', 'access.decide',
  'myaccess.read', 'myaccess.removal.request',
  'review.read', 'team.read', 'secret.rotate', 'audit.read',
  'notifications.read', 'assistant.use',
];

const ADMIN: Permission[] = [
  'app.read', 'app.create', 'app.update', 'app.submit', 'app.decide', 'app.provision',
  'catalog.read', 'access.request', 'access.read', 'access.decide',
  'myaccess.read', 'myaccess.removal.request',
  'review.read', 'team.read', 'team.manage', 'secret.rotate', 'audit.read',
  'notifications.read', 'assistant.use',
];

const AUDITOR: Permission[] = [
  'app.read', 'catalog.read', 'access.read', 'review.read',
  'team.read', 'audit.read', 'notifications.read',
];

const READ_ONLY: Permission[] = [
  'app.read', 'catalog.read', 'access.read', 'myaccess.read',
  'team.read', 'notifications.read',
];

const ALL_PERMISSIONS: Permission[] = [
  'app.read', 'app.create', 'app.update', 'app.submit', 'app.decide', 'app.provision',
  'catalog.read', 'access.request', 'access.read', 'access.decide',
  'myaccess.read', 'myaccess.removal.request',
  'review.read', 'team.read', 'team.manage', 'secret.rotate', 'audit.read',
  'notifications.read', 'impersonate', 'assistant.use',
];

export const ROLE_PERMISSIONS: Record<Role, ReadonlySet<Permission>> = {
  APPLICATION_OWNER: new Set(OWNER),
  SSO_OPERATIONS: new Set(SSO_OPS),
  ADMIN: new Set(ADMIN),
  AUDITOR: new Set(AUDITOR),
  READ_ONLY: new Set(READ_ONLY),
  SUPER_ADMIN: new Set(ALL_PERMISSIONS),
};

/** Union of every permission granted by any of the held roles. */
export function permissionsFor(roles: readonly Role[]): Set<Permission> {
  const union = new Set<Permission>();
  for (const role of roles) {
    for (const perm of ROLE_PERMISSIONS[role] ?? []) {
      union.add(perm);
    }
  }
  return union;
}
