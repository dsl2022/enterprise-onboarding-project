import { riskMeta, statusMeta } from './status';

describe('statusMeta — shared vocabulary for both request types', () => {
  it('maps onboarding + access statuses to the shared semantic tones', () => {
    expect(statusMeta('DRAFT')).toEqual({ label: 'Draft', tone: 'neutral' });
    expect(statusMeta('UNDER_REVIEW').tone).toBe('info');
    expect(statusMeta('CHANGES_REQUESTED').tone).toBe('warn');
    expect(statusMeta('REJECTED').tone).toBe('danger');
    expect(statusMeta('ACTIVE').tone).toBe('success'); // onboarding terminal
    expect(statusMeta('GRANTED').tone).toBe('success'); // access terminal
  });

  it('falls back to a sentence-case neutral chip for unknown statuses', () => {
    expect(statusMeta('SOMETHING_NEW')).toEqual({ label: 'Something new', tone: 'neutral' });
  });
});

describe('riskMeta', () => {
  it('maps catalog risk levels to tones', () => {
    expect(riskMeta('LOW').tone).toBe('success');
    expect(riskMeta('MEDIUM').tone).toBe('warn');
    expect(riskMeta('HIGH').tone).toBe('danger');
  });
});
