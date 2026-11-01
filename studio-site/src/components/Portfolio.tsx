import { useState } from 'react';
import { ArrowUpRight, ExternalLink, Gamepad2, Layers, Smartphone } from 'lucide-react';

type Category = 'all' | 'apps' | 'games' | 'b2b';

interface PortfolioItem {
  id: string;
  category: Exclude<Category, 'all'>;
  divisionBadge: string;
  title: string;
  subtitle: string;
  description: string;
  metrics: string[];
  stack: string[];
  image: string;
  liveUrl?: string;
  liveLabel?: string;
}

const ITEMS: PortfolioItem[] = [
  {
    id: 'peersplit',
    category: 'apps',
    divisionBadge: 'Apps Division · Live on Google Play',
    title: 'PeerSplit — P2P Expense & UPI Ledger',
    subtitle: 'Consumer & Prosumer FinTech Application',
    description:
      'Offline-first expense splitting and multi-party ledger with native UPI deep-link reconciliation, QR generation, and tamper-evident local audit logs.',
    metrics: ['100% On-Device Privacy', '2-Tap UPI Settle', 'Zero Ad Telemetry'],
    stack: ['Android', 'Offline Ledger', 'UPI Intent / QR', 'Material Dark UI'],
    image: '/assets/screen_dashboard.png',
    liveUrl: 'https://play.google.com/store/apps/details?id=com.peersplit.app',
    liveLabel: 'View on Google Play',
  },
  {
    id: 'fareforge',
    category: 'games',
    divisionBadge: 'Games Division · Live on Google Play',
    title: 'Fareforge — Mobility Tycoon',
    subtitle: 'Real-Time Transit & Fleet Strategy Simulation',
    description:
      'Tropical civic transit tycoon featuring real-time route dispatch, dynamic weather & district demand engines, 60 FPS isometric rendering, and Google Play Billing.',
    metrics: ['60 FPS Custom Loop', 'Google Play Billing', 'Offline Save Sync'],
    stack: ['Android Native / WebGL', 'TypeScript', 'Capacitor', 'Play Billing v7'],
    image: '/assets/fareforge_feature.png',
    liveUrl: 'https://play.google.com/store/apps/details?id=com.lantern.dispatch',
    liveLabel: 'View on Google Play',
  },
  {
    id: 'khata-portal',
    category: 'b2b',
    divisionBadge: 'B2B Blueprint · Wholesale & Retail',
    title: 'Party Ledger, Khata & GST Dispatch Portal',
    subtitle: 'Custom Web + Mobile Field Staff Suite',
    description:
      'Role-based distributor portal combining party balance tracking, automated WhatsApp/UPI payment reminders, and barcode-ready inventory dispatch.',
    metrics: ['Automated UPI Links', 'Multi-Branch Roles', 'GST PDF Invoices'],
    stack: ['React / TypeScript', 'Flutter Staff App', 'Postgres', 'UPI Webhooks'],
    image: '/assets/screen_ledger.png',
  },
];

export function Portfolio() {
  const [filter, setFilter] = useState<Category>('all');

  const visible = filter === 'all' ? ITEMS : ITEMS.filter((i) => i.category === filter);

  return (
    <section
      id="portfolio"
      aria-labelledby="portfolio-heading"
      className="border-b border-white/[0.07] py-20 sm:py-28"
    >
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="flex flex-col justify-between gap-6 lg:flex-row lg:items-end">
          <div>
            <span className="font-mono text-xs font-semibold uppercase tracking-[0.2em] text-cyan-400">
              Our Work · Apps, Games &amp; B2B Systems
            </span>
            <h2
              id="portfolio-heading"
              className="mt-3 text-3xl font-extrabold tracking-tight text-white sm:text-4xl"
            >
              Shipped Products &amp; Engineering Blueprints
            </h2>
            <p className="mt-3 max-w-2xl text-base text-slate-300">
              Explore our in-house Apps and Games divisions alongside the custom B2B architectures
              we deploy for local businesses.
            </p>
          </div>

          {/* Filter Pills */}
          <div
            role="tablist"
            aria-label="Filter portfolio by division"
            className="inline-flex flex-wrap gap-2 rounded-xl border border-white/10 bg-ink-900/90 p-1.5"
          >
            {[
              { id: 'all', label: 'All Work', icon: Layers },
              { id: 'apps', label: 'Apps (PeerSplit)', icon: Smartphone },
              { id: 'games', label: 'Games (Fareforge)', icon: Gamepad2 },
              { id: 'b2b', label: 'B2B Portals', icon: ArrowUpRight },
            ].map(({ id, label, icon: Icon }) => {
              const active = filter === id;
              return (
                <button
                  key={id}
                  role="tab"
                  type="button"
                  aria-selected={active}
                  onClick={() => setFilter(id as Category)}
                  className={`inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-semibold transition ${
                    active
                      ? 'bg-gradient-to-r from-brand-500 to-cyan-400 text-ink-950 shadow'
                      : 'text-slate-300 hover:text-white'
                  }`}
                >
                  <Icon className="h-3.5 w-3.5" />
                  {label}
                </button>
              );
            })}
          </div>
        </div>

        <div className="mt-12 grid gap-8 lg:grid-cols-3">
          {visible.map((item) => (
            <article
              key={item.id}
              className="glass flex flex-col justify-between overflow-hidden rounded-2xl transition duration-300 hover:-translate-y-1 hover:border-brand-400/40"
            >
              <div>
                <div className="relative h-48 overflow-hidden border-b border-white/10 bg-ink-950">
                  <img
                    src={item.image}
                    alt={item.title}
                    className="h-full w-full object-cover object-top opacity-90 transition duration-500 hover:scale-105"
                  />
                  <span className="absolute left-3 top-3 rounded-full border border-white/15 bg-ink-950/85 px-3 py-1 font-mono text-[11px] font-semibold text-cyan-300 backdrop-blur-md">
                    {item.divisionBadge}
                  </span>
                </div>

                <div className="p-6">
                  <div className="text-xs font-medium text-slate-400">{item.subtitle}</div>
                  <h3 className="mt-1 text-xl font-bold text-white">{item.title}</h3>
                  <p className="mt-3 text-sm leading-relaxed text-slate-300">{item.description}</p>

                  <div className="mt-4 flex flex-wrap gap-2">
                    {item.metrics.map((m) => (
                      <span
                        key={m}
                        className="rounded-md border border-emerald-400/25 bg-emerald-500/10 px-2.5 py-1 font-mono text-[11px] text-emerald-300"
                      >
                        {m}
                      </span>
                    ))}
                  </div>

                  <div className="mt-4 flex flex-wrap gap-1.5 border-t border-white/10 pt-4">
                    {item.stack.map((tech) => (
                      <span
                        key={tech}
                        className="rounded bg-white/[0.05] px-2 py-0.5 font-mono text-[11px] text-slate-300"
                      >
                        {tech}
                      </span>
                    ))}
                  </div>
                </div>
              </div>

              <div className="border-t border-white/10 bg-ink-950/50 px-6 py-4">
                {item.liveUrl ? (
                  <a
                    href={item.liveUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center gap-1.5 text-xs font-bold text-cyan-300 hover:text-white"
                  >
                    {item.liveLabel}
                    <ExternalLink className="h-3.5 w-3.5" />
                  </a>
                ) : (
                  <a
                    href="#contact"
                    className="inline-flex items-center gap-1.5 text-xs font-bold text-brand-300 hover:text-white"
                  >
                    Request a Custom Demo
                    <ArrowUpRight className="h-3.5 w-3.5" />
                  </a>
                )}
              </div>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}
