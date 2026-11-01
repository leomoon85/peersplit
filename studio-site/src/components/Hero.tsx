import { ArrowRight, CheckCircle2, IndianRupee, ShieldCheck, Sparkles, Compass } from 'lucide-react';

const STATS = [
  {
    icon: ShieldCheck,
    label: '100% Privacy-First Stack',
    detail: 'Local-first & zero-telemetry options for sensitive business ledgers',
  },
  {
    icon: IndianRupee,
    label: 'Native UPI & Payment Integration Expertise',
    detail: 'Dynamic QR flows, deep-link intents & automated settlement logs',
  },
  {
    icon: CheckCircle2,
    label: 'Production-Ready Releases',
    detail: 'Live Google Play apps shipped, signed & maintained in-house',
  },
];

export function Hero() {
  return (
    <section
      id="top"
      className="relative overflow-hidden border-b border-white/[0.07] pt-14 pb-20 sm:pt-20 sm:pb-28"
    >
      {/* Ambient studio radial glow */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute -top-40 left-1/2 h-[540px] w-[980px] -translate-x-1/2 rounded-full bg-gradient-to-tr from-brand-600/25 via-cyan-500/15 to-amber-500/10 blur-3xl"
      />

      <div className="relative mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="grid items-center gap-12 lg:grid-cols-12">
          {/* Left column: Agency Value Prop */}
          <div className="lg:col-span-7">
            <div className="inline-flex items-center gap-2 rounded-full border border-brand-400/30 bg-brand-500/10 px-3.5 py-1.5 text-xs font-medium text-brand-300">
              <Sparkles className="h-3.5 w-3.5 text-cyan-400" />
              <span>B2B Software Studio · Mobile Apps, Web Portals &amp; UPI Automation</span>
            </div>

            <h1 className="mt-6 text-4xl font-extrabold tracking-tight text-white sm:text-5xl lg:text-[3.35rem] lg:leading-[1.1]">
              Custom Web &amp; Mobile Apps for{' '}
              <span className="bg-gradient-to-r from-brand-300 via-cyan-300 to-emerald-300 bg-clip-text text-transparent">
                Local Businesses &amp; Growth Teams
              </span>
            </h1>

            <p className="mt-6 max-w-2xl text-base leading-relaxed text-slate-300 sm:text-lg">
              We design, build, and deploy high-performance software, custom billing tools, and
              automated workflows. Powered by the engineering team behind{' '}
              <a
                href="#products"
                className="font-semibold text-white underline decoration-brand-400/70 underline-offset-4 hover:text-brand-300"
              >
                PeerSplit
              </a>
              .
            </p>

            {/* Primary & Secondary CTAs */}
            <div className="mt-8 flex flex-wrap items-center gap-4">
              <a
                href="#contact"
                className="inline-flex items-center gap-2 rounded-xl bg-gradient-to-r from-brand-500 to-cyan-400 px-6 py-3.5 text-sm font-bold text-ink-950 shadow-glow transition hover:brightness-110"
              >
                Request a Free Consultation
                <ArrowRight className="h-4 w-4" />
              </a>
              <a
                href="#portfolio"
                className="inline-flex items-center gap-2 rounded-xl border border-white/15 bg-white/[0.04] px-6 py-3.5 text-sm font-semibold text-white transition hover:border-white/30 hover:bg-white/[0.08]"
              >
                <Compass className="h-4 w-4 text-cyan-400" />
                Explore Our Work
              </a>
            </div>

            <div className="mt-7 flex flex-wrap items-center gap-x-6 gap-y-2 text-xs text-slate-400">
              <span className="inline-flex items-center gap-1.5">
                <span className="h-2 w-2 rounded-full bg-emerald-400" />
                Fixed-scope milestones
              </span>
              <span>•</span>
              <span>Interactive prototype in 7 days</span>
              <span>•</span>
              <span>Direct senior engineer access</span>
            </div>
          </div>

          {/* Right column: Live Engineering Architecture Showcase Card */}
          <div className="lg:col-span-5">
            <div className="glass relative rounded-2xl p-6 shadow-glow">
              <div className="flex items-center justify-between border-b border-white/10 pb-4">
                <div className="flex items-center gap-3">
                  <img
                    src="/assets/peerloom_brand_logo.png"
                    alt="Peerloom Studio"
                    className="h-9 w-9 rounded-lg object-contain"
                    onError={(e) => {
                      (e.currentTarget as HTMLImageElement).style.display = 'none';
                    }}
                  />
                  <div>
                    <div className="text-xs font-semibold uppercase tracking-wider text-cyan-400">
                      Live Studio Telemetry
                    </div>
                    <div className="text-sm font-bold text-white">
                      Production Delivery Blueprint
                    </div>
                  </div>
                </div>
                <span className="inline-flex items-center gap-1.5 rounded-full border border-emerald-400/30 bg-emerald-500/10 px-2.5 py-1 font-mono text-[11px] font-medium text-emerald-300">
                  <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 animate-pulse" />
                  2 Live Google Play Apps
                </span>
              </div>

              <div className="mt-5 space-y-3.5">
                <div className="rounded-xl border border-white/10 bg-ink-950/70 p-3.5">
                  <div className="flex items-center justify-between text-xs">
                    <span className="font-mono uppercase tracking-wider text-slate-400">
                      01 · FinTech &amp; Ledger Core
                    </span>
                    <span className="rounded bg-brand-500/20 px-2 py-0.5 font-mono text-[11px] text-brand-300">
                      PeerSplit Engine
                    </span>
                  </div>
                  <p className="mt-1.5 text-sm font-semibold text-white">
                    Offline-first SQLite / CRDT sync + Instant UPI Deep-Link Reconciliation
                  </p>
                </div>

                <div className="rounded-xl border border-white/10 bg-ink-950/70 p-3.5">
                  <div className="flex items-center justify-between text-xs">
                    <span className="font-mono uppercase tracking-wider text-slate-400">
                      02 · B2B Operations Suite
                    </span>
                    <span className="rounded bg-cyan-500/20 px-2 py-0.5 font-mono text-[11px] text-cyan-300">
                      Web + Android + iOS
                    </span>
                  </div>
                  <p className="mt-1.5 text-sm font-semibold text-white">
                    Custom Party Ledgers, GST Billing, Field Staff Apps &amp; Admin Portals
                  </p>
                </div>

                <div className="rounded-xl border border-white/10 bg-ink-950/70 p-3.5">
                  <div className="flex items-center justify-between text-xs">
                    <span className="font-mono uppercase tracking-wider text-slate-400">
                      03 · Interactive &amp; Game Systems
                    </span>
                    <span className="rounded bg-amber-500/20 px-2 py-0.5 font-mono text-[11px] text-amber-300">
                      Fareforge Engine
                    </span>
                  </div>
                  <p className="mt-1.5 text-sm font-semibold text-white">
                    60 FPS real-time simulation, custom shaders &amp; Google Play Billing
                  </p>
                </div>
              </div>

              <div className="mt-5 flex items-center justify-between rounded-xl border border-brand-400/20 bg-brand-500/10 px-4 py-3 text-xs text-slate-200">
                <span>Want an instant ballpark for your build?</span>
                <a
                  href="#pricing"
                  className="font-semibold text-cyan-300 hover:text-cyan-200 underline underline-offset-4"
                >
                  Launch Estimator →
                </a>
              </div>
            </div>
          </div>
        </div>

        {/* Stats Bar */}
        <div
          aria-label="Key studio capabilities"
          className="mt-14 grid gap-4 sm:grid-cols-3"
        >
          {STATS.map(({ icon: Icon, label, detail }) => (
            <div
              key={label}
              className="glass flex items-start gap-4 rounded-2xl p-5 transition hover:border-brand-400/35"
            >
              <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl border border-brand-400/30 bg-brand-500/15 text-cyan-300">
                <Icon className="h-5 w-5" />
              </div>
              <div>
                <div className="text-sm font-bold text-white sm:text-base">{label}</div>
                <p className="mt-1 text-xs leading-relaxed text-slate-400">{detail}</p>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
