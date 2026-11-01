export type AppTypeId = 'mobile' | 'web' | 'automation' | 'suite';
export type IntegrationId = 'upi' | 'cloud' | 'admin';
export type SupportId = 'launch' | 'retainer';

export interface PriceOption<T extends string> {
  id: T;
  label: string;
  description: string;
  cost: number; // INR
  weeks: number;
}

/** All prices in INR. Edit here to change the estimator everywhere. */
export const APP_TYPES: PriceOption<AppTypeId>[] = [
  { id: 'mobile', label: 'Mobile App', description: 'Android & iOS (Flutter / React Native)', cost: 150000, weeks: 6 },
  { id: 'web', label: 'Web Portal', description: 'Customer or staff portal, responsive web', cost: 90000, weeks: 4 },
  { id: 'automation', label: 'Automation Tool', description: 'Billing, ledgers, inventory workflows', cost: 60000, weeks: 3 },
  { id: 'suite', label: 'Full Suite', description: 'Mobile app + web portal + admin', cost: 300000, weeks: 10 },
];

export const INTEGRATIONS: PriceOption<IntegrationId>[] = [
  { id: 'upi', label: 'UPI Payments', description: 'Dynamic QR, intent flows, reconciliation', cost: 35000, weeks: 1.5 },
  { id: 'cloud', label: 'Database / Cloud', description: 'Managed backend, auth, backups', cost: 25000, weeks: 1 },
  { id: 'admin', label: 'Custom Admin Dashboard', description: 'Reports, roles, exports', cost: 45000, weeks: 2 },
];

export const SUPPORT: { id: SupportId; label: string; description: string; monthly: number }[] = [
  { id: 'launch', label: 'Launch Only', description: '30-day post-launch bug-fix warranty', monthly: 0 },
  { id: 'retainer', label: 'Monthly Maintenance Retainer', description: 'Updates, monitoring, store releases', monthly: 12000 },
];

/** ±15% band around the computed point estimate. */
export const RANGE_BAND = 0.15;
