import {
  APP_TYPES,
  INTEGRATIONS,
  SUPPORT,
  type AppTypeId,
  type IntegrationId,
  type SupportId,
} from '../data/pricing';

export interface ScopeSelection {
  appType: AppTypeId;
  integrations: IntegrationId[];
  support: SupportId;
}

export interface ScopeEstimate {
  appLabel: string;
  integrationLabels: string[];
  supportLabel: string;
  totalWeeksMin: number;
  totalWeeksMax: number;
  milestonesCount: number;
  complexity: 'Standard' | 'Medium' | 'Advanced';
}

export function calculateScope(sel: ScopeSelection): ScopeEstimate {
  const app = APP_TYPES.find((a) => a.id === sel.appType);
  if (!app) throw new Error(`Unknown app type: ${sel.appType}`);

  const uniqueInts = Array.from(new Set(sel.integrations));
  let weeks = app.sprintWeeks;
  const integrationLabels: string[] = [];

  for (const id of uniqueInts) {
    const opt = INTEGRATIONS.find((i) => i.id === id);
    if (opt) {
      weeks += opt.sprintWeeks;
      integrationLabels.push(opt.label);
    }
  }

  const support = SUPPORT.find((s) => s.id === sel.support);

  const totalWeeksMin = Math.max(2, Math.floor(weeks * 0.9));
  const totalWeeksMax = Math.ceil(weeks * 1.25);
  const milestonesCount = totalWeeksMax <= 4 ? 2 : totalWeeksMax <= 8 ? 3 : 4;

  let complexity: 'Standard' | 'Medium' | 'Advanced' = app.complexity;
  if (uniqueInts.length >= 2 || sel.appType === 'suite') {
    complexity = 'Advanced';
  }

  return {
    appLabel: app.label,
    integrationLabels,
    supportLabel: support?.label ?? 'Standard Launch',
    totalWeeksMin,
    totalWeeksMax,
    milestonesCount,
    complexity,
  };
}
