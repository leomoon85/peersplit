import { ExternalLink, Mail } from 'lucide-react';

export function Footer() {
  const year = new Date().getFullYear();

  return (
    <footer className="bg-ink-950 py-14 text-slate-400">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="grid gap-10 border-b border-white/10 pb-10 sm:grid-cols-2 lg:grid-cols-4">
          {/* Col 1: Brand */}
          <div>
            <div className="flex items-center gap-3">
              <img
                src="/assets/peerloom_brand_logo.png"
                alt="Peerloom Labs"
                className="h-9 w-9 rounded-xl object-contain"
                onError={(e) => {
                  (e.currentTarget as HTMLImageElement).style.display = 'none';
                }}
              />
              <span className="text-lg font-extrabold text-white">
                Peerloom <span className="text-brand-400">Labs</span>
              </span>
            </div>
            <p className="mt-3 text-xs leading-relaxed text-slate-400">
              Full-service B2B software studio and product engineering lab. Builders of PeerSplit
              and Fareforge — Mobility Tycoon.
            </p>
            <a
              href="mailto:peerloomlabs@gmail.com"
              className="mt-4 inline-flex items-center gap-1.5 text-xs font-semibold text-cyan-300 hover:text-white"
            >
              <Mail className="h-3.5 w-3.5" />
              peerloomlabs@gmail.com
            </a>
          </div>

          {/* Col 2: Navigation */}
          <div>
            <div className="font-mono text-xs font-semibold uppercase tracking-wider text-white">
              Studio Navigation
            </div>
            <ul className="mt-3 space-y-2 text-xs">
              <li>
                <a href="#services" className="hover:text-white">
                  Services
                </a>
              </li>
              <li>
                <a href="#portfolio" className="hover:text-white">
                  Portfolio (Our Work)
                </a>
              </li>
              <li>
                <a href="#products" className="hover:text-white">
                  PeerSplit Case Study
                </a>
              </li>
              <li>
                <a href="#process" className="hover:text-white">
                  How We Work
                </a>
              </li>
              <li>
                <a href="#pricing" className="hover:text-white">
                  Interactive Cost Estimator
                </a>
              </li>
              <li>
                <a href="#contact" className="hover:text-white">
                  Request a Proposal
                </a>
              </li>
            </ul>
          </div>

          {/* Col 3: Live Google Play Releases */}
          <div>
            <div className="font-mono text-xs font-semibold uppercase tracking-wider text-white">
              Live Production Releases
            </div>
            <ul className="mt-3 space-y-2.5 text-xs">
              <li>
                <a
                  href="https://play.google.com/store/apps/details?id=com.peersplit.app"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1 text-slate-300 hover:text-white"
                >
                  PeerSplit (Android / UPI)
                  <ExternalLink className="h-3 w-3 text-cyan-400" />
                </a>
              </li>
              <li>
                <a
                  href="https://play.google.com/store/apps/details?id=com.lantern.dispatch"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1 text-slate-300 hover:text-white"
                >
                  Fareforge — Mobility Tycoon
                  <ExternalLink className="h-3 w-3 text-amber-400" />
                </a>
              </li>
            </ul>
          </div>

          {/* Col 4: Legal & Policies */}
          <div>
            <div className="font-mono text-xs font-semibold uppercase tracking-wider text-white">
              Legal &amp; Privacy
            </div>
            <ul className="mt-3 space-y-2 text-xs">
              <li>
                <a href="/privacy.html" className="hover:text-white">
                  PeerSplit Privacy Policy
                </a>
              </li>
              <li>
                <a href="/fareforge-privacy.html" className="hover:text-white">
                  Fareforge Privacy Policy
                </a>
              </li>
              <li>
                <a href="/terms.html" className="hover:text-white">
                  Terms of Service
                </a>
              </li>
            </ul>
          </div>
        </div>

        <div className="mt-8 flex flex-col items-center justify-between gap-4 text-xs text-slate-500 sm:flex-row">
          <p>© {year} Peerloom Labs. All rights reserved.</p>
          <p>Engineered with privacy-first architecture in India · peerloomlabs.com</p>
        </div>
      </div>
    </footer>
  );
}
