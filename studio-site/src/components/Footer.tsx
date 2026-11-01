import { ExternalLink, Mail } from 'lucide-react';

export function Footer() {
  const year = new Date().getFullYear();

  return (
    <footer className="border-t border-slate-200 bg-white py-14 text-slate-600">
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="grid gap-10 border-b border-slate-200 pb-10 sm:grid-cols-2 lg:grid-cols-4">
          {/* Col 1: Brand */}
          <div>
            <div className="flex items-center gap-3">
              <img
                src="/assets/peerloom_brand_logo.png"
                alt="Peerloom Labs"
                className="h-9 w-9 rounded-xl object-contain shadow-sm"
                onError={(e) => {
                  (e.currentTarget as HTMLImageElement).style.display = 'none';
                }}
              />
              <span className="text-lg font-extrabold text-slate-900">
                Peerloom <span className="text-blue-600">Labs</span>
              </span>
            </div>
            <p className="mt-3 text-xs leading-relaxed text-slate-600">
              Full-service B2B software studio and product engineering lab. Builders of PeerSplit
              and Fareforge — Mobility Tycoon.
            </p>
            <a
              href="mailto:peerloomlabs@gmail.com"
              className="mt-4 inline-flex items-center gap-1.5 text-xs font-semibold text-blue-600 hover:text-blue-800"
            >
              <Mail className="h-3.5 w-3.5" />
              peerloomlabs@gmail.com
            </a>
          </div>

          {/* Col 2: Navigation */}
          <div>
            <div className="font-mono text-xs font-bold uppercase tracking-wider text-slate-900">
              Studio Navigation
            </div>
            <ul className="mt-3 space-y-2 text-xs">
              <li>
                <a href="#services" className="hover:text-blue-600">
                  Services
                </a>
              </li>
              <li>
                <a href="#portfolio" className="hover:text-blue-600">
                  Our Work
                </a>
              </li>
              <li>
                <a href="#products" className="hover:text-blue-600">
                  PeerSplit Case Study
                </a>
              </li>
              <li>
                <a href="#process" className="hover:text-blue-600">
                  How We Work
                </a>
              </li>
              <li>
                <a href="#planner" className="hover:text-blue-600">
                  Scope Planner
                </a>
              </li>
              <li>
                <a href="#contact" className="hover:text-blue-600">
                  Request a Proposal
                </a>
              </li>
            </ul>
          </div>

          {/* Col 3: Live Google Play Releases */}
          <div>
            <div className="font-mono text-xs font-bold uppercase tracking-wider text-slate-900">
              Live Production Apps
            </div>
            <ul className="mt-3 space-y-2.5 text-xs">
              <li>
                <a
                  href="https://play.google.com/store/apps/details?id=com.peersplit.app"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1 text-slate-700 hover:text-blue-600 font-medium"
                >
                  PeerSplit (Android / UPI)
                  <ExternalLink className="h-3 w-3 text-blue-600" />
                </a>
              </li>
              <li>
                <a
                  href="https://play.google.com/store/apps/details?id=com.lantern.dispatch"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1 text-slate-700 hover:text-amber-600 font-medium"
                >
                  Fareforge — Mobility Tycoon
                  <ExternalLink className="h-3 w-3 text-amber-600" />
                </a>
              </li>
            </ul>
          </div>

          {/* Col 4: Legal & Policies */}
          <div>
            <div className="font-mono text-xs font-bold uppercase tracking-wider text-slate-900">
              Legal &amp; Compliance
            </div>
            <ul className="mt-3 space-y-2 text-xs">
              <li>
                <a href="/privacy.html" className="hover:text-blue-600">
                  PeerSplit Privacy Policy
                </a>
              </li>
              <li>
                <a href="/fareforge-privacy.html" className="hover:text-blue-600">
                  Fareforge Privacy Policy
                </a>
              </li>
              <li>
                <a href="/terms.html" className="hover:text-blue-600">
                  Terms of Service
                </a>
              </li>
            </ul>
          </div>
        </div>

        <div className="mt-8 flex flex-col items-center justify-between gap-4 text-xs text-slate-600 sm:flex-row">
          <p>© {year} Peerloom Labs. All rights reserved.</p>
          <p>Engineered with privacy-first architecture in India · peerloomlabs.com</p>
        </div>
      </div>
    </footer>
  );
}
