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
    detail: 'Responsive interface optimized for one-handed operation and fast local entry.',
  },
  {
    icon: RefreshCw,
    title: 'P2P Local Ledger Sync',
    detail: 'Deterministic group split math with multi-party balances and offline data persistence.',
  },
  {
    icon: IndianRupee,
    title: 'Seamless UPI Reconciliation',
    detail: 'Direct UPI intent deep-links and dynamic QR codes that settle dues with zero manual tracking.',
  },
  {
    icon: Lock,
    title: 'Privacy-First Architecture',
    detail: 'No ad trackers, zero third-party data broker sharing, and full on-device data safety.',
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
    caption: 'Tamper-evident record of all group adjustments',
    src: '/assets/screen_audit.png',
  },
];

export function PeerSplitSpotlight() {
  const [activeScreen, setActiveScreen] = useState(SCREENS[0]);

  return (
    <section
      id="products"
      aria-labelledby="peersplit-heading"
      className="border-b border-slate-200 bg-slate-50/70 py-20 sm:py-28"
    >
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="grid items-center gap-12 lg:grid-cols-12">
          {/* Left column: Case Study Copy + Highlights + Callout Box */}
          <div className="lg:col-span-7">
            <div className="badge-emerald">
              <Sparkles className="h-3.5 w-3.5 text-emerald-600" />
              <span>In-House Production Benchmark · Live on Google Play</span>
            </div>

            <h2
              id="peersplit-heading"
              className="mt-4 text-3xl font-extrabold tracking-tight text-slate-900 sm:text-4xl"
            >
              Our In-House Proof of Quality: PeerSplit
            </h2>

            <p className="mt-4 text-base leading-relaxed text-slate-600">
              Rather than generic slide decks, we invite prospective clients to inspect our live
              production code on Google Play. <strong className="text-slate-900">PeerSplit</strong> is
              our flagship privacy-first expense sharing application—built with the exact same
              engineering rigor, speed, and UPI integrations we deliver for our B2B clients.
            </p>

            {/* 4 Highlights */}
            <div className="mt-8 grid gap-4 sm:grid-cols-2">
              {HIGHLIGHTS.map(({ icon: Icon, title, detail }) => (
                <div key={title} className="card-base p-4">
                  <div className="flex items-center gap-3">
                    <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-emerald-50 text-emerald-600">
                      <Icon className="h-5 w-5" />
                    </div>
                    <h3 className="text-sm font-bold text-slate-900">{title}</h3>
                  </div>
                  <p className="mt-2 text-xs leading-relaxed text-slate-600">{detail}</p>
                </div>
              ))}
            </div>

            {/* Required Callout Box */}
            <div className="mt-8 rounded-2xl border border-emerald-300 bg-gradient-to-r from-emerald-50 to-blue-50 p-6 shadow-sm">
              <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-center">
                <div className="flex items-start gap-3.5">
                  <ShieldCheck className="mt-0.5 h-6 w-6 shrink-0 text-emerald-600" />
                  <div>
                    <p className="text-sm font-bold text-slate-900 sm:text-base">
                      “Want to verify our design and code quality before hiring us? Download our
                      live app on Google Play.”
                    </p>
                    <p className="mt-1 text-xs text-slate-600">
                      Test our interface fluidity, offline ledger performance, and UPI flows directly.
                    </p>
                  </div>
                </div>

                <a
                  href={PLAY_STORE_URL}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="btn-primary shrink-0 bg-emerald-600 hover:bg-emerald-700"
                >
                  Get PeerSplit on Google Play
                  <ExternalLink className="h-4 w-4" />
                </a>
              </div>
            </div>
          </div>

          {/* Right column: Interactive Live Screenshot Switcher */}
          <div className="lg:col-span-5">
            <div className="card-base p-5 shadow-elevated">
              <div className="flex items-center justify-between border-b border-slate-100 pb-3">
                <div className="flex items-center gap-2.5">
                  <img
                    src="/assets/logo.png"
                    alt="PeerSplit icon"
                    className="h-8 w-8 rounded-lg object-contain shadow-sm"
                    onError={(e) => {
                      (e.currentTarget as HTMLImageElement).style.display = 'none';
                    }}
                  />
                  <div>
                    <div className="text-xs font-bold text-slate-900">PeerSplit for Android</div>
                    <div className="font-mono text-[11px] text-emerald-600 font-medium">
                      com.peersplit.app
                    </div>
                  </div>
                </div>
                <span className="badge-emerald">VERIFIED RELEASE</span>
              </div>

              {/* Screen Tabs */}
              <div
                role="tablist"
                aria-label="PeerSplit screenshots"
                className="mt-4 grid grid-cols-4 gap-1.5 rounded-xl bg-slate-100 p-1"
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
                          ? 'bg-white text-slate-900 shadow-sm'
                          : 'text-slate-600 hover:text-slate-900'
                      }`}
                    >
                      {s.label}
                    </button>
                  );
                })}
              </div>

              {/* Screenshot Preview */}
              <div className="mt-4 flex flex-col items-center rounded-xl border border-slate-200/80 bg-slate-950 p-4 shadow-inner">
                <img
                  src={activeScreen.src}
                  alt={`PeerSplit ${activeScreen.label} screen`}
                  className="max-h-[380px] w-auto rounded-lg object-contain shadow-2xl"
                />
                <p className="mt-3 text-center text-xs text-slate-300 font-medium">
                  {activeScreen.caption}
                </p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
