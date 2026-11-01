import { describe, expect, it } from 'vitest';
import { calculateScope } from './estimate';
import { EMPTY_LEAD, validateStep } from './validation';

describe('calculateScope', () => {
  it('calculates baseline scope for mobile app', () => {
    const s = calculateScope({ appType: 'mobile', integrations: [], support: 'launch' });
    expect(s.totalWeeksMin).toBe(5);
    expect(s.totalWeeksMax).toBe(8);
    expect(s.milestonesCount).toBe(3);
    expect(s.complexity).toBe('Advanced');
  });

  it('adds integrations and adjusts milestones count', () => {
    const s = calculateScope({
      appType: 'automation',
      integrations: ['upi', 'admin'],
      support: 'retainer',
    });
    expect(s.integrationLabels).toContain('UPI & Payment Flows');
    expect(s.integrationLabels).toContain('Admin & Role Management');
    expect(s.totalWeeksMax).toBeGreaterThan(6);
  });
});

describe('validateStep', () => {
  it('flags missing contact fields on step 0', () => {
    const e = validateStep(EMPTY_LEAD, 0);
    expect(Object.keys(e).sort()).toEqual(['businessName', 'email', 'fullName']);
  });

  it('accepts a complete lead', () => {
    const lead = {
      ...EMPTY_LEAD,
      fullName: 'Asha Rao',
      businessName: 'Rao Traders',
      email: 'asha@raotraders.in',
      phone: '+91 98450 12345',
      service: 'Custom Mobile App Development',
      budget: '1-3L',
      details: 'We need a billing app with UPI QR collection for 3 stores.',
    };
    expect(validateStep(lead, 2)).toEqual({});
  });
});
