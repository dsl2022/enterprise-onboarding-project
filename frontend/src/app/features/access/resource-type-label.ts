import { ResourceType } from '../../core/api/models';

/** Human labels for catalog resource types. */
const LABELS: Record<ResourceType, string> = {
  AWS: 'AWS',
  WORKDAY: 'Workday',
  ROLE: 'Role',
  TEAM: 'Team',
};

export function resourceTypeLabel(type: ResourceType): string {
  return LABELS[type] ?? type;
}
