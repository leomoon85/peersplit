import { useState } from 'react';
import {
  ExternalLink,
  IndianRupee,
  Lock,
  RefreshCw,
  ShieldCheck,
  Smartphone,
  Sparkles,
} from 'lucide-react';

const PLAY_STORE_URL = 'https://play.google.com/store/apps/details?id=com.peersplit.app';

const HIGHLIGHTS = [
  {
    icon: Smartphone,
    title: 'Mobile Android App',
    detail: 'Polished dark-mode native interface engineered for instant one-handed entry and zero lag.',
  },
  {
    icon: RefreshCw,
    title: 'P2P Local Ledger Sync',
    detail: 'Deterministic group split engine with multi-party balances and offline-first persistence.',
  },
  {
    icon: IndianRupee,
    title: 'Seamless UPI Reconciliation',
    detail: 'Direct UPI intent links & dynamic QR codes that settle group dues in two taps.',
  },
  {
    icon: Lock,
    title: 'Privacy-First Architecture',
    detail: 'No ad trackers, no data brokerage, and full local audit trails on the user’s device.',
  },
];

const SCREENS = [
  {
    id: 'dashboard',
    label: 'Dashboard',
    caption: 'Real-time net balances & active split groups',
    src: '/assets/screen_dashboard.png',
  },
  {
    id: 'ledger',
    label: 'Group Ledger',
    caption: 'Multi-member expense splits & instant category tags',
    src: '/assets/screen_ledger.png',
  },
  {
    id: 'settle',
    label: 'UPI Settlement',
    caption: 'One-tap UPI intent & QR reconciliation flow',
    src: '/assets/screen_settle.png',
  },
  {
    id: 'audit',
    label: 'Audit Trail',
    caption: 'Tamper-evident history of every edit and settlement',
    src: '/assets/screen_audit.png',
  },
];

export function PeerSplitSpotlight() {
  const [activeScreen, setActiveScreen] = useState(SCREENS[0]);

  return (
    <section
      id="products"
      aria-labelledby="peersplit-heading"
      className="relative overflow-hidden border-b border-white/[0.07] py-20 sm:py-28"
    >
      <div
        aria-hidden="true"
        className="pointer-events-none absolute right-0 top-1/3 h-96 w-96 rounded-full bg-emerald-500/10 blur-3xl"
      />

      <div className="relative mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="grid items-center gap-12 lg:grid-cols-12">
          {/* Left column: Case Study Copy + Highlights + Callout Box */}
          <div className="lg:col-span-7">
            <div className="inline-flex items-center gap-2 rounded-full border border-emerald-400/30 bg-emerald-500/10 px-3.5 py-1 text-xs font-semibold text-emerald-300">
              <Sparkles className="h-3.5 w-3.5" />
              <span>Featured Live Product · Shipped &amp; Maintained by Peerloom Labs</span>
            </div>

            <h2
              id="peersplit-heading"
              className="mt-4 text-3xl font-extrabold tracking-tight text-white sm:text-4xl"
            >
              Our In-House Proof of Quality: PeerSplit
            </h2>

            <p className="mt-4 text-base leading-relaxed text-slate-300">
              Most agencies show static mockups. We point you to our live production software on
              Google Play. <strong className="text-white">PeerSplit</strong> is our flagship
              privacy-first P2P expense sharing and UPI settlement application—built with the exact
              same ledger accuracy, speed, and security we bring to our B2B client projects.
            </p>

            {/* 4 Key Highlights */}
            <div className="mt-8 grid gap-4 sm:grid-cols-2">
              {HIGHLIGHTS.map(({ icon: Icon, title, detail }) => (
                <div
                  key={title}
                  className="glass rounded-xl p-4 transition hover:border-emerald-400/35"
                >
                  <div className="flex items-center gap-3">
                    <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-emerald-500/15 text-emerald-300">
                      <Icon className="h-5 w-5" />
                    </div>
                    <h3 className="text-sm font-bold text-white">{title}</h3>
                  </div>
                  <p className="mt-2 text-xs leading-relaxed text-slate-300">{detail}</p>
                </div>
              ))}
            </div>

            {/* Required Callout Box */}
            <div className="mt-8 rounded-2xl border border-emerald-400/35 bg-gradient-to-r from-emerald-500/15 via-brand-500/10 to-cyan-500/10 p-5 sm:p-6">
              <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
                <div className="flex items-start gap-3.5">
                  <ShieldCheck className="mt-0.5 h-6 w-6 shrink-0 text-emerald-400" />
                  <div>
                    <p className="text-sm font-semibold text-white sm:text-base">
                      “Want to verify our design and code quality before hiring us? Download our
                      live app on Google Play.”
                    </p>
                    <p className="mt-1 text-xs text-slate-300">
                      Test our UI responsiveness, offline ledger speed, and UPI flows firsthand.
                    </p>
                  </div>
                </div>

                <a
                  href={PLAY_STORE_URL}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex shrink-0 items-center justify-center gap-2 rounded-xl bg-emerald-400 px-5 py-3 text-sm font-bold text-ink-950 shadow-lg transition hover:bg-emerald-300"
                >
                  Get PeerSplit on Google Play
                  <ExternalLink className="h-4 w-4" />
                </a>
              </div>
            </div>
          </div>

          {/* Right column: Interactive Live Screenshot Switcher */}
          <div className="lg:col-span-5">
            <div className="glass rounded-2xl p-5">
              <div className="flex items-center justify-between border-b border-white/10 pb-3">
                <div className="flex items-center gap-2.5">
                  <img
                    src="/assets/logo.png"
                    alt="PeerSplit icon"
                    className="h-7 w-7 rounded-lg object-contain"
                    onError={(e) => {
                      (e.currentTarget as HTMLImageElement).style.display = 'none';
                    }}
                  />
                  <div>
                    <div className="text-xs font-bold text-white">PeerSplit Android Build</div>
                    <div className="font-mono text-[11px] text-emerald-400">com.peersplit.app</div>
                  </div>
                </div>
                <span className="rounded-full bg-emerald-500/15 px-2.5 py-0.5 font-mono text-[11px] font-medium text-emerald-300">
                  LIVE ON PLAY STORE
                </span>
              </div>

              {/* Screen Tabs */}
              <div
                role="tablist"
                aria-label="PeerSplit screenshots"
                className="mt-4 grid grid-cols-4 gap-1.5 rounded-xl bg-ink-950/80 p-1"
              >
                {SCREENS.map((s) => {
                  const selected = s.id === activeScreen.id;
                  return (
                    <button
                      key={s.id}
                      role="tab"
                      type="button"
                      aria-selected={selected}
                      onClick={() => setActiveScreen(s)}
                      className={`rounded-lg px-2 py-1.5 text-xs font-semibold transition ${
                        selected
                          ? 'bg-gradient-to-r from-brand-500 to-cyan-400 text-ink-950 shadow'
                          : 'text-slate-300 hover:text-white'
                      }`}
                    >
                      {s.label}
                    </button>
                  );
                })}
              </div>

              {/* Screenshot Preview */}
              <div className="mt-4 flex flex-col items-center rounded-xl border border-white/10 bg-ink-950/90 p-4">
                <img
                  src={activeScreen.src}
                  alt={`PeerSplit ${activeScreen.label} screen`}
                  className="max-h-[380px] w-auto rounded-xl border border-white/10 object-contain shadow-2xl"
                />
                <p className="mt-3 text-center text-xs text-slate-300">{activeScreen.caption}</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
