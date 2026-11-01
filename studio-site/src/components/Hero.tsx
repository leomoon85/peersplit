import { ArrowRight, CheckCircle2, IndianRupee, ShieldCheck, Sparkles, FolderGit2 } from 'lucide-react';

const STATS = [
  {
    icon: ShieldCheck,
    label: '100% Privacy-First Architecture',
    detail: 'Secure local-first ledgers, zero data selling, and complete business ownership',
  },
  {
    icon: IndianRupee,
    label: 'Native UPI & Payment Integration',
    detail: 'Dynamic QR collection, app-to-app UPI deep links & automated reconciliation',
  },
  {
    icon: CheckCircle2,
    label: 'Production-Ready Releases',
    detail: 'Live apps published, signed, and maintained on the Google Play Store',
  },
];

export function Hero() {
  return (
    <section
      id="top"
      className="relative overflow-hidden border-b border-slate-200 bg-gradient-to-b from-white via-slate-50 to-slate-100/70 pt-14 pb-20 sm:pt-20 sm:pb-28"
    >
      {/* Soft corporate background aura */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute -top-40 left-1/2 h-[500px] w-[900px] -translate-x-1/2 rounded-full bg-gradient-to-tr from-blue-100/60 via-indigo-50/40 to-emerald-100/40 blur-3xl"
      />

      <div className="relative mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="grid items-center gap-12 lg:grid-cols-12">
          {/* Left column: Agency Value Prop */}
          <div className="lg:col-span-7">
            <div className="badge-blue">
              <Sparkles className="h-3.5 w-3.5 text-blue-600" />
              <span>Full-Service Software Studio &amp; B2B IT Consultancy</span>
            </div>

            <h1 className="mt-5 text-4xl font-extrabold tracking-tight text-slate-900 sm:text-5xl lg:text-[3.25rem] lg:leading-[1.15]">
              Custom Web &amp; Mobile Apps for{' '}
              <span className="text-blue-600">Local Businesses &amp; Growth Teams</span>
            </h1>

            <p className="mt-5 max-w-2xl text-base leading-relaxed text-slate-600 sm:text-lg">
              We design, build, and deploy high-performance software, custom billing tools, and
              automated workflows. Powered by the engineering team behind{' '}
              <a
                href="#products"
                className="font-semibold text-slate-900 underline decoration-blue-500 underline-offset-4 hover:text-blue-600"
              >
                PeerSplit
              </a>
              .
            </p>

            {/* Primary & Secondary CTAs */}
            <div className="mt-8 flex flex-wrap items-center gap-4">
              <a href="#contact" className="btn-primary">
                Request a Free Consultation
                <ArrowRight className="h-4 w-4" />
              </a>
              <a href="#portfolio" className="btn-secondary">
                <FolderGit2 className="h-4 w-4 text-slate-500" />
                Explore Our Work
              </a>
            </div>

            <div className="mt-7 flex flex-wrap items-center gap-x-6 gap-y-2 text-xs font-medium text-slate-600">
              <span className="inline-flex items-center gap-1.5">
                <span className="h-2 w-2 rounded-full bg-emerald-500" />
                Transparent Milestone Roadmaps
              </span>
              <span>•</span>
              <span>Clickable Prototype in Week 1</span>
              <span>•</span>
              <span>Direct Senior Engineer Access</span>
            </div>
          </div>

          {/* Right column: Production Blueprint Card */}
          <div className="lg:col-span-5">
            <div className="card-base p-6 shadow-elevated">
              <div className="flex items-center justify-between border-b border-slate-100 pb-4">
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
                    <div className="text-xs font-semibold uppercase tracking-wider text-blue-600">
                      Engineering Blueprint
                    </div>
                    <div className="text-sm font-bold text-slate-900">
                      In-House Production Quality
                    </div>
                  </div>
                </div>
                <span className="badge-emerald">
                  <span className="h-1.5 w-1.5 rounded-full bg-emerald-500 animate-pulse" />
                  Live On Google Play
                </span>
              </div>

              <div className="mt-5 space-y-3">
                <div className="rounded-xl border border-slate-200/80 bg-slate-50/80 p-3.5">
                  <div className="flex items-center justify-between text-xs">
                    <span className="font-semibold uppercase tracking-wider text-slate-600">
                      01 · FinTech &amp; Ledger Sync
                    </span>
                    <span className="rounded bg-blue-100 px-2 py-0.5 font-mono text-[11px] font-semibold text-blue-700">
                      PeerSplit
                    </span>
                  </div>
                  <p className="mt-1 text-sm font-medium text-slate-800">
                    Offline-first SQLite ledger + instant UPI deep-link reconciliation
                  </p>
                </div>

                <div className="rounded-xl border border-slate-200/80 bg-slate-50/80 p-3.5">
                  <div className="flex items-center justify-between text-xs">
                    <span className="font-semibold uppercase tracking-wider text-slate-600">
                      02 · B2B Operations Suite
                    </span>
                    <span className="rounded bg-indigo-100 px-2 py-0.5 font-mono text-[11px] font-semibold text-indigo-700">
                      Web + Mobile
                    </span>
                  </div>
                  <p className="mt-1 text-sm font-medium text-slate-800">
                    Party ledgers, khata books, GST invoices, and field staff portals
                  </p>
                </div>

                <div className="rounded-xl border border-slate-200/80 bg-slate-50/80 p-3.5">
                  <div className="flex items-center justify-between text-xs">
                    <span className="font-semibold uppercase tracking-wider text-slate-600">
                      03 · Real-Time Simulation
                    </span>
                    <span className="rounded bg-amber-100 px-2 py-0.5 font-mono text-[11px] font-semibold text-amber-700">
                      Fareforge
                    </span>
                  </div>
                  <p className="mt-1 text-sm font-medium text-slate-800">
                    High-performance 60 FPS state engine &amp; Google Play Billing v7
                  </p>
                </div>
              </div>

              <div className="mt-5 flex items-center justify-between rounded-xl bg-blue-50/70 px-4 py-3 text-xs text-slate-700">
                <span>Want to map out your project scope?</span>
                <a
                  href="#planner"
                  className="font-semibold text-blue-600 hover:text-blue-800 underline underline-offset-4"
                >
                  Open Scope Planner →
                </a>
              </div>
            </div>
          </div>
        </div>

        {/* Stats Bar */}
        <div
          aria-label="Key studio capabilities"
          className="mt-14 grid gap-5 sm:grid-cols-3"
        >
          {STATS.map(({ icon: Icon, label, detail }) => (
            <div
              key={label}
              className="card-base card-hover flex items-start gap-4 p-5"
            >
              <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-blue-50 text-blue-600">
                <Icon className="h-5 w-5" />
              </div>
              <div>
                <div className="text-sm font-bold text-slate-900 sm:text-base">{label}</div>
                <p className="mt-1 text-xs leading-relaxed text-slate-600">{detail}</p>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
