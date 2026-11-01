import { describe, expect, it } from 'vitest';
import { estimate, formatINR } from './estimate';
import { EMPTY_LEAD, validateStep } from './validation';

describe('estimate', () => {
  it('prices a bare mobile app with a ±15% band', () => {
    const e = estimate({ appType: 'mobile', integrations: [], support: 'launch' });
    expect(e.costLow).toBe(130000); // 127,500 → nearest 5k
    expect(e.costHigh).toBe(175000); // 172,500 → nearest 5k (half-up)
    expect(e.weeksLow).toBe(5);
    expect(e.weeksHigh).toBe(8);
    expect(e.monthly).toBe(0);
  });

  it('adds integrations once even if duplicated', () => {
    const a = estimate({ appType: 'web', integrations: ['upi', 'admin'], support: 'launch' });
    const b = estimate({ appType: 'web', integrations: ['upi', 'admin', 'upi'], support: 'launch' });
    expect(a).toEqual(b);
    expect(a.costHigh).toBeGreaterThan(estimate({ appType: 'web', integrations: [], support: 'launch' }).costHigh);
  });

  it('reports retainer as a separate monthly line', () => {
    expect(estimate({ appType: 'automation', integrations: [], support: 'retainer' }).monthly).toBe(12000);
  });

  it('formats INR with Indian grouping', () => {
    expect(formatINR(150000)).toBe('₹1,50,000');
  });
});

describe('validateStep', () => {
  it('flags missing contact fields on step 0 only', () => {
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
      service: 'mobile',
      budget: '1-3L',
      details: 'We need a billing app with UPI QR collection for 3 stores.',
    };
    expect(validateStep(lead, 2)).toEqual({});
  });

  it('rejects malformed email and phone', () => {
    const e = validateStep({ ...EMPTY_LEAD, fullName: 'A B', businessName: 'Biz', email: 'nope', phone: 'abc' }, 0);
    expect(e.email).toBeDefined();
    expect(e.phone).toBeDefined();
  });
});
