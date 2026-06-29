import { RequestStatus, Risk } from '../core/api/models';

/** Semantic tone → maps to the --status-* token pairs in _tokens.scss. */
export type Tone = 'neutral' | 'info' | 'warn' | 'success' | 'danger';

interface StatusMeta {
  label: string;
  tone: Tone;
}

/**
 * One status vocabulary for BOTH request types (onboarding + access), so the
 * status chip and timeline look identical across the shared workflow:
 *   draft = neutral · submitted/under-review/approved/provisioning = info
 *   changes-requested = warning · active/granted = success · rejected = danger
 */
const STATUS_META: Record<RequestStatus, StatusMeta> = {
  DRAFT: { label: 'Draft', tone: 'neutral' },
  SUBMITTED: { label: 'Submitted', tone: 'info' },
  UNDER_REVIEW: { label: 'Under review', tone: 'info' },
  CHANGES_REQUESTED: { label: 'Changes requested', tone: 'warn' },
  APPROVED: { label: 'Approved', tone: 'info' },
  PROVISIONING: { label: 'Provisioning', tone: 'info' },
  REJECTED: { label: 'Rejected', tone: 'danger' },
  ACTIVE: { label: 'Active', tone: 'success' },
  GRANTED: { label: 'Granted', tone: 'success' },
};

export function statusMeta(status: string): StatusMeta {
  return STATUS_META[status as RequestStatus] ?? { label: sentence(status), tone: 'neutral' };
}

const RISK_META: Record<Risk, StatusMeta> = {
  LOW: { label: 'Low', tone: 'success' },
  MEDIUM: { label: 'Medium', tone: 'warn' },
  HIGH: { label: 'High', tone: 'danger' },
};

export function riskMeta(risk: Risk): StatusMeta {
  return RISK_META[risk] ?? { label: sentence(risk), tone: 'neutral' };
}

/** Fallback: TYPE_LIKE_THIS → "Type like this" (sentence case house style). */
function sentence(raw: string): string {
  const text = raw.replace(/_/g, ' ').toLowerCase();
  return text.charAt(0).toUpperCase() + text.slice(1);
}
