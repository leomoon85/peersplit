import {
  APP_TYPES,
  INTEGRATIONS,
  RANGE_BAND,
  SUPPORT,
  type AppTypeId,
  type IntegrationId,
  type SupportId,
} from '../data/pricing';

export interface Selection {
  appType: AppTypeId;
  integrations: IntegrationId[];
  support: SupportId;
}

export interface Estimate {
  costLow: number;
  costHigh: number;
  weeksLow: number;
  weeksHigh: number;
  monthly: number;
}

const roundTo = (n: number, step: number) => Math.round(n / step) * step;

export function estimate(sel: Selection): Estimate {
  const app = APP_TYPES.find((a) => a.id === sel.appType);
  if (!app) throw new Error(`Unknown app type: ${sel.appType}`);
  const unique = Array.from(new Set(sel.integrations));
  let cost = app.cost;
  let weeks = app.weeks;
  for (const id of unique) {
    const opt = INTEGRATIONS.find((i) => i.id === id);
    if (!opt) throw new Error(`Unknown integration: ${id}`);
    cost += opt.cost;
    weeks += opt.weeks;
  }
  const support = SUPPORT.find((s) => s.id === sel.support);
  if (!support) throw new Error(`Unknown support plan: ${sel.support}`);
  return {
    costLow: roundTo(cost * (1 - RANGE_BAND), 5000),
    costHigh: roundTo(cost * (1 + RANGE_BAND), 5000),
    weeksLow: Math.max(1, Math.floor(weeks * 0.9)),
    weeksHigh: Math.ceil(weeks * 1.2),
    monthly: support.monthly,
  };
}

export const formatINR = (n: number) =>
  '₹' + new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 }).format(n);
