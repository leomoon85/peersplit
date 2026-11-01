export type AppTypeId = 'mobile' | 'web' | 'automation' | 'suite';
export type IntegrationId = 'upi' | 'cloud' | 'admin';
export type SupportId = 'launch' | 'retainer';

export interface ScopeOption<T extends string> {
  id: T;
  label: string;
  description: string;
  sprintWeeks: number;
  complexity: 'Standard' | 'Medium' | 'Advanced';
}

export const APP_TYPES: ScopeOption<AppTypeId>[] = [
  {
    id: 'mobile',
    label: 'Custom Mobile App',
    description: 'Android & iOS cross-platform native app (Flutter / React Native)',
    sprintWeeks: 6,
    complexity: 'Advanced',
  },
  {
    id: 'web',
    label: 'Custom Web Portal',
    description: 'Customer or internal web portal with role-based access & reports',
    sprintWeeks: 4,
    complexity: 'Medium',
  },
  {
    id: 'automation',
    label: 'Business Automation Tool',
    description: 'Automated billing, party ledger tracking & daily workflow tools',
    sprintWeeks: 3,
    complexity: 'Standard',
  },
  {
    id: 'suite',
    label: 'Full Platform Suite',
    description: 'Mobile App + Web Portal + Executive Admin Operations Suite',
    sprintWeeks: 10,
    complexity: 'Advanced',
  },
];

export const INTEGRATIONS: ScopeOption<IntegrationId>[] = [
  {
    id: 'upi',
    label: 'UPI & Payment Flows',
    description: 'Dynamic UPI QR codes, intent deep-links & settlement logs',
    sprintWeeks: 1.5,
    complexity: 'Standard',
  },
  {
    id: 'cloud',
    label: 'Cloud & Database Sync',
    description: 'Multi-device sync, secure authentication & automated backups',
    sprintWeeks: 1,
    complexity: 'Standard',
  },
  {
    id: 'admin',
    label: 'Admin & Role Management',
    description: 'Staff permissions, audit trails, CSV/PDF report exports',
    sprintWeeks: 2,
    complexity: 'Medium',
  },
];

export const SUPPORT: { id: SupportId; label: string; description: string; tag: string }[] = [
  {
    id: 'launch',
    label: 'Standard Launch Warranty',
    description: '30-day post-launch bug fixes, handover & deployment sign-off',
    tag: 'Included',
  },
  {
    id: 'retainer',
    label: 'Dedicated Engineering Retainer',
    description: 'Ongoing OS updates, feature sprints, uptime monitoring & priority support',
    tag: 'Recommended for Active Operations',
  },
];
