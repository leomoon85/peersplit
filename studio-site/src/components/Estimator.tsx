import { useState } from 'react';
import { ArrowRight, Check, Clock, Layers, Sparkles, Sliders } from 'lucide-react';
import {
  APP_TYPES,
  INTEGRATIONS,
  SUPPORT,
  type AppTypeId,
  type IntegrationId,
  type SupportId,
} from '../data/pricing';
import { calculateScope } from '../lib/estimate';

export interface ScopePrefill {
  service: string;
  budget: string;
  summary: string;
}

interface ScopePlannerProps {
  onApplyToForm: (prefill: ScopePrefill) => void;
}

function mapAppTypeToService(appType: AppTypeId): string {
  switch (appType) {
    case 'mobile':
      return 'Custom Mobile App Development';
    case 'web':
    case 'automation':
      return 'Business Automation & Web Portals';
    case 'suite':
      return 'Full Product Suite (Mobile + Web + UPI)';
  }
}

export function Estimator({ onApplyToForm }: ScopePlannerProps) {
  const [appType, setAppType] = useState<AppTypeId>('mobile');
  const [integrations, setIntegrations] = useState<IntegrationId[]>(['upi', 'cloud']);
  const [support, setSupport] = useState<SupportId>('retainer');

  const toggleIntegration = (id: IntegrationId) => {
    setIntegrations((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]
    );
  };

  const scope = calculateScope({ appType, integrations, support });

  const handleApplyScope = () => {
    const summary = `Planned Project Scope:\n• Platform: ${scope.appLabel}\n• Modules: ${
      scope.integrationLabels.join(', ') || 'Core Platform Only'
    }\n• Estimated Timeline: ${scope.totalWeeksMin} – ${
      scope.totalWeeksMax
    } Weeks (${scope.milestonesCount} Milestones)\n• Support Tier: ${scope.supportLabel}`;

    onApplyToForm({
      service: mapAppTypeToService(appType),
      budget: 'unsure', // Let client discuss on call or select their target range
      summary,
    });
  };

  return (
    <section
      id="planner"
      aria-labelledby="planner-heading"
      className="border-b border-slate-200 bg-white py-20 sm:py-28"
    >
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="max-w-2xl">
          <span className="badge-blue">
            <Sliders className="h-3.5 w-3.5 text-blue-600" />
            <span>Interactive Project Scope &amp; Milestone Planner</span>
          </span>
          <h2
            id="planner-heading"
            className="mt-3 text-3xl font-extrabold tracking-tight text-slate-900 sm:text-4xl"
          >
            Configure Your Scope &amp; Delivery Roadmap
          </h2>
          <p className="mt-3 text-base text-slate-600">
            Select your target platform, operational modules, and post-launch support plan. We’ll
            map out the development sprints and prepare a tailored milestone proposal for your
            business.
          </p>
        </div>

        <div className="mt-12 grid gap-8 lg:grid-cols-12">
          {/* Left 7 columns: Interactive Selection Controls */}
          <div className="space-y-8 lg:col-span-7">
            {/* Step 1: Platform Selection */}
            <div>
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-bold uppercase tracking-wider text-slate-900">
                  1. Target Platform &amp; Application Type
                </h3>
                <span className="text-xs text-slate-500 font-medium">Select one</span>
              </div>
              <div className="mt-3 grid gap-3 sm:grid-cols-2">
                {APP_TYPES.map((item) => {
                  const selected = appType === item.id;
                  return (
                    <button
                      key={item.id}
                      type="button"
                      aria-pressed={selected}
                      onClick={() => setAppType(item.id)}
                      className={`flex flex-col items-start rounded-2xl border p-4 text-left transition-all ${
                        selected
                          ? 'border-blue-600 bg-blue-50/60 shadow-sm ring-1 ring-blue-600'
                          : 'border-slate-200 bg-white hover:border-slate-300 hover:bg-slate-50/50'
                      }`}
                    >
                      <div className="flex w-full items-center justify-between">
                        <span className="text-base font-bold text-slate-900">{item.label}</span>
                        <span className="rounded-full bg-slate-100 px-2.5 py-0.5 font-mono text-[11px] font-semibold text-slate-700">
                          ~{item.sprintWeeks}w base
                        </span>
                      </div>
                      <p className="mt-1.5 text-xs text-slate-600 leading-relaxed">
                        {item.description}
                      </p>
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Step 2: Integrations & Modules */}
            <div>
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-bold uppercase tracking-wider text-slate-900">
                  2. Operational Modules &amp; Integrations
                </h3>
                <span className="text-xs text-slate-500 font-medium">Multi-select</span>
              </div>
              <div className="mt-3 grid gap-3 sm:grid-cols-3">
                {INTEGRATIONS.map((item) => {
                  const checked = integrations.includes(item.id);
                  return (
                    <button
                      key={item.id}
                      type="button"
                      aria-pressed={checked}
                      onClick={() => toggleIntegration(item.id)}
                      className={`flex flex-col justify-between rounded-2xl border p-4 text-left transition-all ${
                        checked
                          ? 'border-emerald-600 bg-emerald-50/60 ring-1 ring-emerald-600'
                          : 'border-slate-200 bg-white hover:border-slate-300 hover:bg-slate-50/50'
                      }`}
                    >
                      <div>
                        <div className="flex items-center justify-between gap-2">
                          <span className="text-sm font-bold text-slate-900">{item.label}</span>
                          <span
                            className={`flex h-5 w-5 items-center justify-center rounded-md border ${
                              checked
                                ? 'border-emerald-600 bg-emerald-600 text-white'
                                : 'border-slate-300 bg-white'
                            }`}
                          >
                            {checked && <Check className="h-3.5 w-3.5 stroke-[3]" />}
                          </span>
                        </div>
                        <p className="mt-1.5 text-xs text-slate-600 leading-relaxed">
                          {item.description}
                        </p>
                      </div>
                      <div className="mt-3 pt-2 border-t border-slate-100 font-mono text-[11px] font-semibold text-emerald-700">
                        +{item.sprintWeeks}w sprint
                      </div>
                    </button>
                  );
                })}
              </div>
            </div>

            {/* Step 3: Support */}
            <div>
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-bold uppercase tracking-wider text-slate-900">
                  3. Deployment &amp; Post-Launch Support Tier
                </h3>
                <span className="text-xs text-slate-500 font-medium">Select one</span>
              </div>
              <div className="mt-3 grid gap-3 sm:grid-cols-2">
                {SUPPORT.map((item) => {
                  const selected = support === item.id;
                  return (
                    <button
                      key={item.id}
                      type="button"
                      aria-pressed={selected}
                      onClick={() => setSupport(item.id)}
                      className={`flex flex-col items-start rounded-2xl border p-4 text-left transition-all ${
                        selected
                          ? 'border-blue-600 bg-blue-50/60 shadow-sm ring-1 ring-blue-600'
                          : 'border-slate-200 bg-white hover:border-slate-300 hover:bg-slate-50/50'
                      }`}
                    >
                      <div className="flex w-full items-center justify-between">
                        <span className="text-sm font-bold text-slate-900">{item.label}</span>
                        <span className="rounded bg-blue-100 px-2 py-0.5 text-[10px] font-bold text-blue-800">
                          {item.tag}
                        </span>
                      </div>
                      <p className="mt-1.5 text-xs text-slate-600 leading-relaxed">
                        {item.description}
                      </p>
                    </button>
                  );
                })}
              </div>
            </div>
          </div>

          {/* Right 5 columns: Live Scope & Roadmap Card */}
          <div className="lg:col-span-5">
            <div className="card-base sticky top-24 p-6 sm:p-7 shadow-elevated border-blue-200 bg-gradient-to-b from-white to-blue-50/30">
              <div className="flex items-center justify-between border-b border-slate-200 pb-4">
                <span className="font-mono text-xs font-bold uppercase tracking-wider text-blue-700">
                  Project Delivery Blueprint
                </span>
                <span className="badge-blue">
                  <Sparkles className="h-3 w-3 text-blue-600" /> Fixed Milestones
                </span>
              </div>

              <div className="mt-6">
                <div className="text-xs font-semibold uppercase tracking-wider text-slate-500">
                  Target Scope Summary
                </div>
                <div className="mt-1.5 text-2xl font-extrabold text-slate-900">
                  {scope.appLabel}
                </div>
                <p className="mt-1 text-xs text-slate-600">
                  Includes {scope.integrationLabels.length} added module(s) · {scope.supportLabel}
                </p>
              </div>

              <div className="mt-6 grid grid-cols-2 gap-3.5 rounded-xl border border-slate-200 bg-white p-4 shadow-sm">
                <div>
                  <div className="flex items-center gap-1.5 text-xs font-medium text-slate-500">
                    <Clock className="h-3.5 w-3.5 text-blue-600" />
                    <span>Est. Delivery</span>
                  </div>
                  <div className="mt-1 text-lg font-extrabold text-slate-900">
                    {scope.totalWeeksMin} – {scope.totalWeeksMax} Weeks
                  </div>
                </div>

                <div>
                  <div className="flex items-center gap-1.5 text-xs font-medium text-slate-500">
                    <Layers className="h-3.5 w-3.5 text-blue-600" />
                    <span>Sprint Milestones</span>
                  </div>
                  <div className="mt-1 text-lg font-extrabold text-slate-900">
                    {scope.milestonesCount} Clear Phases
                  </div>
                </div>
              </div>

              <ul className="mt-6 space-y-2.5 text-xs text-slate-700">
                <li className="flex items-center gap-2">
                  <Check className="h-4 w-4 text-emerald-600 shrink-0" />
                  <span>Interactive clickable prototype in Week 1</span>
                </li>
                <li className="flex items-center gap-2">
                  <Check className="h-4 w-4 text-emerald-600 shrink-0" />
                  <span>100% full source-code and intellectual property handover</span>
                </li>
                <li className="flex items-center gap-2">
                  <Check className="h-4 w-4 text-emerald-600 shrink-0" />
                  <span>Bi-weekly demo builds deployed directly to your phone</span>
                </li>
              </ul>

              <button
                type="button"
                onClick={handleApplyScope}
                className="btn-primary mt-7 w-full shadow-md"
              >
                Attach Scope to Proposal Request
                <ArrowRight className="h-4 w-4" />
              </button>
              <p className="mt-2.5 text-center text-[11px] text-slate-600 font-medium">
                Tailored budget and fixed timeline provided in writing after discovery.
              </p>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
