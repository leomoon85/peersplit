import { useState } from 'react';
import { ArrowRight, Calculator, Check, Clock, IndianRupee, Sparkles } from 'lucide-react';
import {
  APP_TYPES,
  INTEGRATIONS,
  SUPPORT,
  type AppTypeId,
  type IntegrationId,
  type SupportId,
} from '../data/pricing';
import { estimate, formatINR } from '../lib/estimate';

export interface EstimatorPrefill {
  service: string;
  budget: string;
  summary: string;
}

interface EstimatorProps {
  onApplyToForm: (prefill: EstimatorPrefill) => void;
}

function mapCostToBudgetBand(costHigh: number): string {
  if (costHigh < 100000) return '<1L';
  if (costHigh <= 300000) return '1-3L';
  if (costHigh <= 600000) return '3-6L';
  return '6L+';
}

function mapAppTypeToService(appType: AppTypeId): string {
  switch (appType) {
    case 'mobile':
      return 'Custom Mobile App Development';
    case 'web':
    case 'automation':
      return 'Business Automation & Web Portals';
    case 'suite':
      return 'Custom Mobile App Development';
  }
}

export function Estimator({ onApplyToForm }: EstimatorProps) {
  const [appType, setAppType] = useState<AppTypeId>('mobile');
  const [integrations, setIntegrations] = useState<IntegrationId[]>(['upi', 'cloud']);
  const [support, setSupport] = useState<SupportId>('retainer');

  const toggleIntegration = (id: IntegrationId) => {
    setIntegrations((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]
    );
  };

  const result = estimate({ appType, integrations, support });

  const handleLockEstimate = () => {
    const appLabel = APP_TYPES.find((a) => a.id === appType)?.label ?? appType;
    const intLabels =
      integrations
        .map((id) => INTEGRATIONS.find((i) => i.id === id)?.label)
        .filter(Boolean)
        .join(', ') || 'None';
    const supportLabel = SUPPORT.find((s) => s.id === support)?.label ?? support;

    const summary = `Estimated Configuration:\n• App Type: ${appLabel}\n• Integrations: ${intLabels}\n• Support Plan: ${supportLabel}\n• Estimated Range: ${formatINR(
      result.costLow
    )} – ${formatINR(result.costHigh)} (${result.weeksLow}–${result.weeksHigh} weeks)`;

    onApplyToForm({
      service: mapAppTypeToService(appType),
      budget: mapCostToBudgetBand(result.costHigh),
      summary,
    });
  };

  return (
    <section
      id="pricing"
      aria-labelledby="estimator-heading"
      className="border-b border-white/[0.07] py-20 sm:py-28"
    >
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="max-w-2xl">
          <span className="inline-flex items-center gap-1.5 font-mono text-xs font-semibold uppercase tracking-[0.2em] text-cyan-400">
            <Calculator className="h-3.5 w-3.5" />
            Interactive Project Cost &amp; Timeline Estimator
          </span>
          <h2
            id="estimator-heading"
            className="mt-3 text-3xl font-extrabold tracking-tight text-white sm:text-4xl"
          >
            Configure Your Build &amp; See Instant Pricing
          </h2>
          <p className="mt-3 text-base text-slate-300">
            Select your platform, required integrations, and post-launch support tier to calculate a
            transparent ballpark range before our discovery call.
          </p>
        </div>

        <div className="mt-12 grid gap-8 lg:grid-cols-12">
          {/* Left 7 columns: Interactive Controls */}
          <div className="space-y-8 lg:col-span-7">
            {/* Step 1: App Type */}
            <div>
              <h3 className="text-sm font-bold uppercase tracking-wider text-slate-200">
                1. Choose App Type
              </h3>
              <div className="mt-3 grid gap-3 sm:grid-cols-2">
                {APP_TYPES.map((item) => {
                  const selected = appType === item.id;
                  return (
                    <button
                      key={item.id}
                      type="button"
                      aria-pressed={selected}
                      onClick={() => setAppType(item.id)}
                      className={`flex flex-col items-start rounded-2xl border p-4 text-left transition ${
                        selected
                          ? 'border-cyan-400 bg-brand-500/15 shadow-glow'
                          : 'border-white/10 bg-ink-900/60 hover:border-white/25'
                      }`}
                    >
                      <div className="flex w-full items-center justify-between">
                        <span className="text-base font-bold text-white">{item.label}</span>
                        <span className="font-mono text-xs font-semibold text-cyan-300">
                          {formatINR(item.cost)}
                        </span>
                      </div>
                      <p className="mt-1 text-xs text-slate-300">{item.description}</p>
                      <span className="mt-2.5 font-mono text-[11px] text-slate-400">
                        ~{item.weeks} weeks base build
                      </span>
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Step 2: Integrations */}
            <div>
              <h3 className="text-sm font-bold uppercase tracking-wider text-slate-200">
                2. Select Integrations (Multi-Select)
              </h3>
              <div className="mt-3 grid gap-3 sm:grid-cols-3">
                {INTEGRATIONS.map((item) => {
                  const checked = integrations.includes(item.id);
                  return (
                    <button
                      key={item.id}
                      type="button"
                      aria-pressed={checked}
                      onClick={() => toggleIntegration(item.id)}
                      className={`flex flex-col justify-between rounded-2xl border p-4 text-left transition ${
                        checked
                          ? 'border-emerald-400/70 bg-emerald-500/10'
                          : 'border-white/10 bg-ink-900/60 hover:border-white/25'
                      }`}
                    >
                      <div>
                        <div className="flex items-center justify-between gap-2">
                          <span className="text-sm font-bold text-white">{item.label}</span>
                          <span
                            className={`flex h-5 w-5 items-center justify-center rounded-md border ${
                              checked
                                ? 'border-emerald-400 bg-emerald-400 text-ink-950'
                                : 'border-white/20'
                            }`}
                          >
                            {checked && <Check className="h-3.5 w-3.5 stroke-[3]" />}
                          </span>
                        </div>
                        <p className="mt-1.5 text-xs text-slate-300">{item.description}</p>
                      </div>
                      <div className="mt-3 pt-2 border-t border-white/10 font-mono text-xs text-emerald-300">
                        +{formatINR(item.cost)} · +{item.weeks}w
                      </div>
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Step 3: Support */}
            <div>
              <h3 className="text-sm font-bold uppercase tracking-wider text-slate-200">
                3. Post-Launch Support &amp; Maintenance
              </h3>
              <div className="mt-3 grid gap-3 sm:grid-cols-2">
                {SUPPORT.map((item) => {
                  const selected = support === item.id;
                  return (
                    <button
                      key={item.id}
                      type="button"
                      aria-pressed={selected}
                      onClick={() => setSupport(item.id)}
                      className={`flex flex-col items-start rounded-2xl border p-4 text-left transition ${
                        selected
                          ? 'border-cyan-400 bg-brand-500/15'
                          : 'border-white/10 bg-ink-900/60 hover:border-white/25'
                      }`}
                    >
                      <div className="flex w-full items-center justify-between">
                        <span className="text-sm font-bold text-white">{item.label}</span>
                        <span className="font-mono text-xs text-cyan-300">
                          {item.monthly === 0 ? 'Included' : `${formatINR(item.monthly)}/mo`}
                        </span>
                      </div>
                      <p className="mt-1 text-xs text-slate-300">{item.description}</p>
                    </button>
                  );
                })}
              </div>
            </div>
          </div>

          {/* Right 5 columns: Live Summary Card */}
          <div className="lg:col-span-5">
            <div
              aria-live="polite"
              className="glass sticky top-24 rounded-2xl border-brand-400/35 p-6 shadow-glow sm:p-8"
            >
              <div className="flex items-center justify-between border-b border-white/10 pb-4">
                <span className="font-mono text-xs uppercase tracking-wider text-cyan-300">
                  Live Project Estimate
                </span>
                <span className="inline-flex items-center gap-1 rounded-full bg-brand-500/20 px-2.5 py-0.5 font-mono text-[11px] text-brand-200">
                  <Sparkles className="h-3 w-3" /> ±15% Fixed-Scope Band
                </span>
              </div>

              <div className="mt-6">
                <div className="text-xs font-medium uppercase tracking-wider text-slate-400">
                  Estimated Build Investment
                </div>
                <div className="mt-2 flex items-baseline gap-2 text-3xl font-extrabold text-white sm:text-4xl">
                  <IndianRupee className="h-7 w-7 text-cyan-400 self-center" />
                  <span>
                    {formatINR(result.costLow)} – {formatINR(result.costHigh)}
                  </span>
                </div>
                <p className="mt-1 text-xs text-slate-400">
                  One-time milestone-based build cost · GST extra as applicable
                </p>
              </div>

              <div className="mt-6 grid grid-cols-2 gap-4 rounded-xl border border-white/10 bg-ink-950/70 p-4">
                <div>
                  <div className="flex items-center gap-1.5 text-xs text-slate-400">
                    <Clock className="h-3.5 w-3.5 text-cyan-400" />
                    <span>Delivery Timeline</span>
                  </div>
                  <div className="mt-1 text-lg font-bold text-white">
                    {result.weeksLow} – {result.weeksHigh} Weeks
                  </div>
                </div>

                <div>
                  <div className="text-xs text-slate-400">Support Retainer</div>
                  <div className="mt-1 text-lg font-bold text-white">
                    {result.monthly === 0 ? 'Launch Warranty' : `${formatINR(result.monthly)} / mo`}
                  </div>
                </div>
              </div>

              <ul className="mt-6 space-y-2 text-xs text-slate-300">
                <li className="flex items-center gap-2">
                  <Check className="h-4 w-4 text-emerald-400" />
                  <span>Clickable Figma / live prototype delivered in Week 1</span>
                </li>
                <li className="flex items-center gap-2">
                  <Check className="h-4 w-4 text-emerald-400" />
                  <span>Full source-code &amp; Play Store / cloud ownership</span>
                </li>
                <li className="flex items-center gap-2">
                  <Check className="h-4 w-4 text-emerald-400" />
                  <span>30-day post-launch bug-fix warranty included</span>
                </li>
              </ul>

              <button
                type="button"
                onClick={handleLockEstimate}
                className="mt-7 inline-flex w-full items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-brand-500 to-cyan-400 px-5 py-3.5 text-sm font-bold text-ink-950 shadow-glow transition hover:brightness-110"
              >
                Attach This Estimate to Proposal Form
                <ArrowRight className="h-4 w-4" />
              </button>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
