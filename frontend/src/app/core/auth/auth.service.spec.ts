import { effectiveRoles } from './auth.service';
import { permissionsFor } from './permissions';
import { Me } from '../api/models';

function me(partial: Partial<Me>): Me {
  return {
    id: 'u1',
    name: 'Test',
    email: 't@example.com',
    role: 'READ_ONLY',
    roles: [],
    group: null,
    isSuperAdmin: false,
    impersonating: null,
    ...partial,
  };
}

describe('effectiveRoles — impersonation reduces UI capability (Q-003 safe)', () => {
  it('returns ONLY the impersonated role even if roles[] still holds SUPER_ADMIN', () => {
    // Models the "backend did NOT pre-reduce roles[]" interpretation of the contract.
    const principal = me({ roles: ['SUPER_ADMIN'], isSuperAdmin: true, impersonating: { role: 'READ_ONLY' } });
    expect(effectiveRoles(principal)).toEqual(['READ_ONLY']);

    const perms = permissionsFor(effectiveRoles(principal));
    expect(perms.has('impersonate')).toBeFalse(); // reduced view loses god-mode
    expect(perms.has('app.create')).toBeFalse(); // and owner/admin writes
    expect(perms.has('catalog.read')).toBeTrue(); // keeps what READ_ONLY has
  });

  it('uses the held roles (union) when not impersonating', () => {
    expect(effectiveRoles(me({ roles: ['APPLICATION_OWNER', 'AUDITOR'] }))).toEqual([
      'APPLICATION_OWNER',
      'AUDITOR',
    ]);
  });

  it('returns no roles when signed out', () => {
    expect(effectiveRoles(null)).toEqual([]);
  });
});
