import { Role } from '../core/api/models';

/** Human-friendly, sentence-case labels for the six portal roles. */
const ROLE_LABELS: Record<Role, string> = {
  APPLICATION_OWNER: 'Application owner',
  SSO_OPERATIONS: 'SSO operations',
  ADMIN: 'Admin',
  AUDITOR: 'Auditor',
  READ_ONLY: 'Read-only',
  SUPER_ADMIN: 'Super admin',
};

export function roleLabel(role: Role): string {
  return ROLE_LABELS[role] ?? role;
}
