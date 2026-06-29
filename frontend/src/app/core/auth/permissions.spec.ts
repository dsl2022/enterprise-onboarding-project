import { Permission, permissionsFor, ROLE_PERMISSIONS } from './permissions';
import { Role } from '../api/models';

describe('permissionsFor — RBAC is the UNION of held roles (CR-1056)', () => {
  it('unions permissions across roles, not "most-privileged single role"', () => {
    // APPLICATION_OWNER and AUDITOR grant disjoint things; the union keeps both.
    const perms = permissionsFor(['APPLICATION_OWNER', 'AUDITOR']);
    expect(perms.has('app.create')).toBeTrue(); // only the owner role grants this
    expect(perms.has('audit.read')).toBeTrue(); // only the auditor role grants this
  });

  it('grants `impersonate` to SUPER_ADMIN only', () => {
    expect(permissionsFor(['SUPER_ADMIN']).has('impersonate')).toBeTrue();
    const others: Role[] = ['APPLICATION_OWNER', 'SSO_OPERATIONS', 'ADMIN', 'AUDITOR', 'READ_ONLY'];
    for (const role of others) {
      expect(permissionsFor([role]).has('impersonate')).withContext(role).toBeFalse();
    }
  });

  it('withholds `access.request` from AUDITOR and READ_ONLY', () => {
    expect(permissionsFor(['AUDITOR']).has('access.request')).toBeFalse();
    expect(permissionsFor(['READ_ONLY']).has('access.request')).toBeFalse();
  });

  it('READ_ONLY may read my-access but not request removal', () => {
    const p = permissionsFor(['READ_ONLY']);
    expect(p.has('myaccess.read')).toBeTrue();
    expect(p.has('myaccess.removal.request')).toBeFalse();
  });

  it('returns an empty set for no roles (signed-out / unknown)', () => {
    expect(permissionsFor([]).size).toBe(0);
  });

  it('SUPER_ADMIN is a superset of every other role’s permissions', () => {
    const su = permissionsFor(['SUPER_ADMIN']);
    for (const role of Object.keys(ROLE_PERMISSIONS) as Role[]) {
      for (const perm of ROLE_PERMISSIONS[role]) {
        expect(su.has(perm as Permission)).withContext(`${role}:${perm}`).toBeTrue();
      }
    }
  });
});
