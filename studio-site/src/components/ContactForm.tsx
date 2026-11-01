import { useEffect, useState, type FormEvent } from 'react';
import { CheckCircle2, Mail, Send, ShieldCheck } from 'lucide-react';
import { BUDGETS, SERVICES } from '../data/content';
import { EMPTY_LEAD, validateStep, type FormErrors, type LeadForm } from '../lib/validation';
import type { ScopePrefill } from './Estimator';

interface ContactFormProps {
  prefill: ScopePrefill | null;
}

export function ContactForm({ prefill }: ContactFormProps) {
  const [form, setForm] = useState<LeadForm>(EMPTY_LEAD);
  const [errors, setErrors] = useState<FormErrors>({});
  const [status, setStatus] = useState<'idle' | 'submitting' | 'submitted'>('idle');

  useEffect(() => {
    if (!prefill) return;
    setForm((prev) => ({
      ...prev,
      service: prefill.service || prev.service,
      budget: prefill.budget !== 'unsure' ? prefill.budget : prev.budget || 'unsure',
      details: prefill.summary
        ? prev.details && !prev.details.includes('Planned Project Scope:')
          ? `${prev.details}\n\n${prefill.summary}`
          : prefill.summary
        : prev.details,
    }));
  }, [prefill]);

  const update = <K extends keyof LeadForm>(key: K, value: LeadForm[K]) => {
    setForm((prev) => ({ ...prev, [key]: value }));
    if (errors[key]) {
      setErrors((prev) => {
        const next = { ...prev };
        delete next[key];
        return next;
      });
    }
  };

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (form.website) return; // Honeypot
    const validationErrors = validateStep(form, 2);
    if (Object.keys(validationErrors).length > 0) {
      setErrors(validationErrors);
      return;
    }

    setStatus('submitting');
    setTimeout(() => {
      setStatus('submitted');
    }, 350);
  };

  const buildMailtoHref = () => {
    const subject = encodeURIComponent(
      `[Peerloom Proposal Request] ${form.businessName} — ${form.service}`
    );
    const body = encodeURIComponent(
      `Full Name: ${form.fullName}\nBusiness Name: ${form.businessName}\nWork Email: ${form.email}\nPhone: ${
        form.phone || 'Not provided'
      }\nService Required: ${form.service}\nEstimated Budget: ${form.budget}\n\nProject Scope & Goals:\n${
        form.details
      }`
    );
    return `mailto:peerloomlabs@gmail.com?subject=${subject}&body=${body}`;
  };

  return (
    <section
      id="contact"
      aria-labelledby="contact-heading"
      className="border-b border-slate-200 bg-slate-50/70 py-20 sm:py-28"
    >
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="grid gap-12 lg:grid-cols-12">
          {/* Left column: Consultation Guarantees */}
          <div className="lg:col-span-5">
            <span className="badge-blue">
              <span>Start Your Project</span>
            </span>
            <h2
              id="contact-heading"
              className="mt-3 text-3xl font-extrabold tracking-tight text-slate-900 sm:text-4xl"
            >
              Request a Free Architecture &amp; Milestone Proposal
            </h2>
            <p className="mt-4 text-base leading-relaxed text-slate-600">
              Tell us about your operations, app concept, or current billing bottlenecks. You’ll
              speak directly with the engineers who built PeerSplit and receive a written scope,
              timeline, and fixed milestone quote within 24 business hours.
            </p>

            <div className="mt-8 space-y-4">
              <div className="card-base p-4">
                <div className="text-sm font-bold text-slate-900">Direct Senior Engineer Call</div>
                <p className="mt-1 text-xs text-slate-600">
                  No sales commission pressure—your technical discovery call is handled by our
                  principal systems architect.
                </p>
              </div>
              <div className="card-base p-4">
                <div className="text-sm font-bold text-slate-900">NDA &amp; 100% IP Ownership</div>
                <p className="mt-1 text-xs text-slate-600">
                  Your business ledgers, customer database, and custom source code remain 100%
                  your intellectual property.
                </p>
              </div>
            </div>

            <div className="mt-8 flex items-center gap-3 text-xs text-slate-600">
              <Mail className="h-4 w-4 text-blue-600" />
              <span>Direct studio email: </span>
              <a
                href="mailto:peerloomlabs@gmail.com"
                className="font-semibold text-slate-900 underline underline-offset-4 hover:text-blue-600"
              >
                peerloomlabs@gmail.com
              </a>
            </div>
          </div>

          {/* Right column: Lead Intake Form */}
          <div className="lg:col-span-7">
            <div className="card-base p-6 sm:p-8 shadow-elevated">
              {status === 'submitted' ? (
                <div role="status" className="py-8 text-center">
                  <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-emerald-100 text-emerald-700">
                    <CheckCircle2 className="h-8 w-8" />
                  </div>
                  <h3 className="mt-4 text-2xl font-bold text-slate-900">
                    Proposal Request Packaged
                  </h3>
                  <p className="mx-auto mt-2 max-w-md text-sm text-slate-600">
                    Thank you, <strong className="text-slate-900">{form.fullName}</strong>! We’ve
                    prepared your project brief for <strong className="text-slate-900">{form.businessName}</strong>.
                    Send it directly to our engineering inbox via your mail client or we will follow
                    up within 1 business day.
                  </p>
                  <div className="mt-6 flex flex-wrap justify-center gap-3">
                    <a
                      href={buildMailtoHref()}
                      className="btn-primary"
                    >
                      Send via Email (peerloomlabs@gmail.com)
                      <Send className="h-4 w-4" />
                    </a>
                    <button
                      type="button"
                      onClick={() => {
                        setForm(EMPTY_LEAD);
                        setStatus('idle');
                      }}
                      className="btn-secondary text-xs"
                    >
                      Submit Another Inquiry
                    </button>
                  </div>
                </div>
              ) : (
                <form onSubmit={handleSubmit} noValidate className="space-y-5">
                  {/* Honeypot field */}
                  <input
                    type="text"
                    name="website"
                    tabIndex={-1}
                    autoComplete="off"
                    value={form.website}
                    onChange={(e) => update('website', e.target.value)}
                    className="hidden"
                    aria-hidden="true"
                  />

                  <div className="grid gap-5 sm:grid-cols-2">
                    <div>
                      <label
                        htmlFor="lead-fullName"
                        className="block text-xs font-semibold uppercase tracking-wider text-slate-700"
                      >
                        Full Name *
                      </label>
                      <input
                        id="lead-fullName"
                        type="text"
                        required
                        placeholder="e.g. Rajesh Sharma"
                        value={form.fullName}
                        onChange={(e) => update('fullName', e.target.value)}
                        className="mt-1.5 w-full rounded-xl border border-slate-300 bg-white px-3.5 py-2.5 text-sm text-slate-900 placeholder-slate-400 focus:border-blue-600 focus:ring-1 focus:ring-blue-600 focus:outline-none"
                      />
                      {errors.fullName && (
                        <p className="mt-1 text-xs text-rose-600 font-medium">{errors.fullName}</p>
                      )}
                    </div>

                    <div>
                      <label
                        htmlFor="lead-businessName"
                        className="block text-xs font-semibold uppercase tracking-wider text-slate-700"
                      >
                        Business Name *
                      </label>
                      <input
                        id="lead-businessName"
                        type="text"
                        required
                        placeholder="e.g. Apex Enterprises Pvt Ltd"
                        value={form.businessName}
                        onChange={(e) => update('businessName', e.target.value)}
                        className="mt-1.5 w-full rounded-xl border border-slate-300 bg-white px-3.5 py-2.5 text-sm text-slate-900 placeholder-slate-400 focus:border-blue-600 focus:ring-1 focus:ring-blue-600 focus:outline-none"
                      />
                      {errors.businessName && (
                        <p className="mt-1 text-xs text-rose-600 font-medium">
                          {errors.businessName}
                        </p>
                      )}
                    </div>
                  </div>

                  <div className="grid gap-5 sm:grid-cols-2">
                    <div>
                      <label
                        htmlFor="lead-email"
                        className="block text-xs font-semibold uppercase tracking-wider text-slate-700"
                      >
                        Work Email *
                      </label>
                      <input
                        id="lead-email"
                        type="email"
                        required
                        placeholder="you@company.com"
                        value={form.email}
                        onChange={(e) => update('email', e.target.value)}
                        className="mt-1.5 w-full rounded-xl border border-slate-300 bg-white px-3.5 py-2.5 text-sm text-slate-900 placeholder-slate-400 focus:border-blue-600 focus:ring-1 focus:ring-blue-600 focus:outline-none"
                      />
                      {errors.email && (
                        <p className="mt-1 text-xs text-rose-600 font-medium">{errors.email}</p>
                      )}
                    </div>

                    <div>
                      <label
                        htmlFor="lead-phone"
                        className="block text-xs font-semibold uppercase tracking-wider text-slate-700"
                      >
                        Phone / WhatsApp (Optional)
                      </label>
                      <input
                        id="lead-phone"
                        type="tel"
                        placeholder="+91 98765 43210"
                        value={form.phone}
                        onChange={(e) => update('phone', e.target.value)}
                        className="mt-1.5 w-full rounded-xl border border-slate-300 bg-white px-3.5 py-2.5 text-sm text-slate-900 placeholder-slate-400 focus:border-blue-600 focus:ring-1 focus:ring-blue-600 focus:outline-none"
                      />
                      {errors.phone && (
                        <p className="mt-1 text-xs text-rose-600 font-medium">{errors.phone}</p>
                      )}
                    </div>
                  </div>

                  <div className="grid gap-5 sm:grid-cols-2">
                    <div>
                      <label
                        htmlFor="lead-service"
                        className="block text-xs font-semibold uppercase tracking-wider text-slate-700"
                      >
                        Service Required *
                      </label>
                      <select
                        id="lead-service"
                        required
                        value={form.service}
                        onChange={(e) => update('service', e.target.value)}
                        className="mt-1.5 w-full rounded-xl border border-slate-300 bg-white px-3.5 py-2.5 text-sm text-slate-900 focus:border-blue-600 focus:ring-1 focus:ring-blue-600 focus:outline-none"
                      >
                        <option value="">Select a service…</option>
                        {SERVICES.map((s) => (
                          <option key={s.id} value={s.title}>
                            {s.title}
                          </option>
                        ))}
                        <option value="Full Product Suite (Mobile + Web + UPI)">
                          Full Product Suite (Mobile + Web + UPI)
                        </option>
                      </select>
                      {errors.service && (
                        <p className="mt-1 text-xs text-rose-600 font-medium">{errors.service}</p>
                      )}
                    </div>

                    <div>
                      <label
                        htmlFor="lead-budget"
                        className="block text-xs font-semibold uppercase tracking-wider text-slate-700"
                      >
                        Target Budget Band *
                      </label>
                      <select
                        id="lead-budget"
                        required
                        value={form.budget}
                        onChange={(e) => update('budget', e.target.value)}
                        className="mt-1.5 w-full rounded-xl border border-slate-300 bg-white px-3.5 py-2.5 text-sm text-slate-900 focus:border-blue-600 focus:ring-1 focus:ring-blue-600 focus:outline-none"
                      >
                        <option value="">Select a target budget…</option>
                        {BUDGETS.map((b) => (
                          <option key={b.value} value={b.value}>
                            {b.label}
                          </option>
                        ))}
                      </select>
                      {errors.budget && (
                        <p className="mt-1 text-xs text-rose-600 font-medium">{errors.budget}</p>
                      )}
                    </div>
                  </div>

                  <div>
                    <label
                      htmlFor="lead-details"
                      className="block text-xs font-semibold uppercase tracking-wider text-slate-700"
                    >
                      Project Goals &amp; Scope Requirements *
                    </label>
                    <textarea
                      id="lead-details"
                      rows={4}
                      required
                      placeholder="Tell us what you want to build or automate (at least 20 characters)…"
                      value={form.details}
                      onChange={(e) => update('details', e.target.value)}
                      className="mt-1.5 w-full rounded-xl border border-slate-300 bg-white px-3.5 py-2.5 text-sm text-slate-900 placeholder-slate-400 focus:border-blue-600 focus:ring-1 focus:ring-blue-600 focus:outline-none"
                    />
                    {errors.details && (
                      <p className="mt-1 text-xs text-rose-600 font-medium">{errors.details}</p>
                    )}
                  </div>

                  <div className="flex flex-wrap items-center justify-between gap-4 pt-2">
                    <span className="inline-flex items-center gap-1.5 text-xs text-slate-600">
                      <ShieldCheck className="h-4 w-4 text-emerald-600" />
                      100% confidential · Direct engineering proposal
                    </span>

                    <button
                      type="submit"
                      disabled={status === 'submitting'}
                      className="btn-primary disabled:opacity-60"
                    >
                      {status === 'submitting' ? 'Preparing Request…' : 'Request Architecture Proposal'}
                      <Send className="h-4 w-4" />
                    </button>
                  </div>
                </form>
              )}
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
