import { ArrowUpRight, Check } from 'lucide-react';
import { SERVICES } from '../data/content';

export function Services() {
  return (
    <section
      id="services"
      aria-labelledby="services-heading"
      className="border-b border-white/[0.07] py-20 sm:py-28"
    >
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="flex flex-col justify-between gap-6 lg:flex-row lg:items-end">
          <div className="max-w-2xl">
            <span className="font-mono text-xs font-semibold uppercase tracking-[0.2em] text-cyan-400">
              Core Engineering Capabilities
            </span>
            <h2
              id="services-heading"
              className="mt-3 text-3xl font-extrabold tracking-tight text-white sm:text-4xl"
            >
              End-to-End Software Built Around Your Operations
            </h2>
            <p className="mt-3 text-base text-slate-300">
              Whether you need a customer-facing mobile app, an internal billing &amp; khata portal,
              or automated UPI reconciliation, we ship production-grade systems you own outright.
            </p>
          </div>
          <a
            href="#pricing"
            className="inline-flex items-center gap-1.5 self-start rounded-xl border border-white/15 bg-white/[0.04] px-4 py-2.5 text-sm font-semibold text-slate-200 transition hover:border-white/30 hover:bg-white/[0.08] lg:self-auto"
          >
            Estimate Your Project
            <ArrowUpRight className="h-4 w-4 text-cyan-400" />
          </a>
        </div>

        <div className="mt-12 grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
          {SERVICES.map(({ id, icon: Icon, title, summary, bullets }, idx) => (
            <article
              key={id}
              className="glass group relative flex flex-col justify-between rounded-2xl p-6 transition duration-300 hover:-translate-y-1 hover:border-brand-400/40"
            >
              <div>
                <div className="flex items-center justify-between">
                  <div className="flex h-12 w-12 items-center justify-center rounded-xl border border-brand-400/30 bg-gradient-to-br from-brand-500/20 to-cyan-400/10 text-cyan-300">
                    <Icon className="h-6 w-6" />
                  </div>
                  <span className="font-mono text-xs text-slate-500">0{idx + 1}</span>
                </div>

                <h3 className="mt-5 text-lg font-bold text-white group-hover:text-cyan-200">
                  {title}
                </h3>
                <p className="mt-2.5 text-sm leading-relaxed text-slate-300">{summary}</p>

                <ul className="mt-5 space-y-2 border-t border-white/10 pt-4 text-xs text-slate-300">
                  {bullets.map((b) => (
                    <li key={b} className="flex items-center gap-2">
                      <Check className="h-3.5 w-3.5 shrink-0 text-emerald-400" />
                      <span>{b}</span>
                    </li>
                  ))}
                </ul>
              </div>

              <div className="mt-6 pt-2">
                <a
                  href="#contact"
                  className="inline-flex items-center gap-1 text-xs font-semibold text-cyan-300 transition group-hover:text-white"
                >
                  Discuss this service
                  <ArrowUpRight className="h-3.5 w-3.5" />
                </a>
              </div>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}
