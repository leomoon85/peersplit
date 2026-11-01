import { ArrowUpRight, Check } from 'lucide-react';
import { SERVICES } from '../data/content';

export function Services() {
  return (
    <section
      id="services"
      aria-labelledby="services-heading"
      className="border-b border-slate-200 bg-white py-20 sm:py-28"
    >
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="flex flex-col justify-between gap-6 lg:flex-row lg:items-end">
          <div className="max-w-2xl">
            <span className="font-mono text-xs font-semibold uppercase tracking-wider text-blue-600">
              Core Engineering Services
            </span>
            <h2
              id="services-heading"
              className="mt-2 text-3xl font-extrabold tracking-tight text-slate-900 sm:text-4xl"
            >
              Enterprise-Grade Software Built Around Your Business
            </h2>
            <p className="mt-3 text-base text-slate-600">
              Whether you need a consumer mobile app, an internal billing &amp; party ledger portal,
              or custom UPI payment flows, we engineer systems that you own completely.
            </p>
          </div>
          <a
            href="#planner"
            className="btn-secondary self-start lg:self-auto"
          >
            Plan Your Scope &amp; Milestones
            <ArrowUpRight className="h-4 w-4 text-slate-500" />
          </a>
        </div>

        <div className="mt-12 grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
          {SERVICES.map(({ id, icon: Icon, title, summary, bullets }, idx) => (
            <article
              key={id}
              className="card-base card-hover group flex flex-col justify-between p-6"
            >
              <div>
                <div className="flex items-center justify-between">
                  <div className="flex h-12 w-12 items-center justify-center rounded-xl bg-blue-50 text-blue-600 transition group-hover:bg-blue-600 group-hover:text-white">
                    <Icon className="h-6 w-6" />
                  </div>
                  <span className="font-mono text-xs font-semibold text-slate-400">0{idx + 1}</span>
                </div>

                <h3 className="mt-5 text-lg font-bold text-slate-900 group-hover:text-blue-600">
                  {title}
                </h3>
                <p className="mt-2 text-sm leading-relaxed text-slate-600">{summary}</p>

                <ul className="mt-5 space-y-2 border-t border-slate-100 pt-4 text-xs text-slate-600">
                  {bullets.map((b) => (
                    <li key={b} className="flex items-center gap-2">
                      <Check className="h-3.5 w-3.5 shrink-0 text-emerald-600" />
                      <span>{b}</span>
                    </li>
                  ))}
                </ul>
              </div>

              <div className="mt-6 border-t border-slate-100 pt-3">
                <a
                  href="#contact"
                  className="inline-flex items-center gap-1 text-xs font-semibold text-blue-600 transition hover:text-blue-800"
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
