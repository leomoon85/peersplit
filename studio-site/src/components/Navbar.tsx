import { useState } from 'react';
import { ArrowUpRight, Menu, X } from 'lucide-react';

const LINKS = [
  { href: '#services', label: 'Services' },
  { href: '#portfolio', label: 'Portfolio (Our Work)' },
  { href: '#products', label: 'Products (PeerSplit)' },
  { href: '#process', label: 'Process' },
  { href: '#pricing', label: 'Pricing' },
];

export function Navbar() {
  const [open, setOpen] = useState(false);

  return (
    <header className="sticky top-0 z-50 border-b border-white/[0.08] bg-ink-950/80 backdrop-blur-xl">
      <nav
        aria-label="Primary"
        className="mx-auto flex max-w-7xl items-center justify-between px-4 py-3.5 sm:px-6 lg:px-8"
      >
        <a href="#top" className="flex items-center gap-3 text-white group">
          <span className="relative grid h-10 w-10 place-items-center overflow-hidden rounded-xl border border-white/15 bg-gradient-to-br from-brand-500/20 via-ink-900 to-cyan-400/20 shadow-glow">
            <img
              src="/assets/peerloom_brand_logo.png"
              alt="Peerloom Labs emblem"
              className="h-8 w-8 object-contain transition-transform duration-300 group-hover:scale-105"
              onError={(e) => {
                (e.currentTarget as HTMLImageElement).style.display = 'none';
              }}
            />
          </span>
          <span className="flex flex-col leading-tight">
            <span className="text-base font-extrabold tracking-tight text-white">
              Peerloom <span className="text-brand-400">Labs</span>
            </span>
            <span className="font-mono text-[10px] uppercase tracking-[0.18em] text-slate-400">
              Software Studio · B2B IT
            </span>
          </span>
        </a>

        <ul className="hidden items-center gap-7 text-sm font-medium text-slate-300 lg:flex">
          {LINKS.map((l) => (
            <li key={l.href}>
              <a
                href={l.href}
                className="transition-colors hover:text-white focus-visible:text-white"
              >
                {l.label}
              </a>
            </li>
          ))}
        </ul>

        <div className="hidden items-center gap-3 lg:flex">
          <a
            href="#pricing"
            className="rounded-xl border border-white/10 bg-white/[0.03] px-3.5 py-2 text-xs font-semibold text-slate-200 transition hover:border-white/25 hover:bg-white/[0.07]"
          >
            Cost Estimator
          </a>
          <a
            href="#contact"
            className="inline-flex items-center gap-1.5 rounded-xl bg-gradient-to-r from-brand-500 to-cyan-400 px-4 py-2.5 text-sm font-semibold text-ink-950 shadow-glow transition hover:brightness-110"
          >
            Get a Proposal
            <ArrowUpRight className="h-4 w-4" />
          </a>
        </div>

        <button
          type="button"
          aria-expanded={open}
          aria-controls="mobile-nav"
          aria-label={open ? 'Close navigation menu' : 'Open navigation menu'}
          onClick={() => setOpen((v) => !v)}
          className="inline-flex items-center justify-center rounded-xl border border-white/10 bg-white/[0.04] p-2 text-slate-200 hover:bg-white/10 lg:hidden"
        >
          {open ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
        </button>
      </nav>

      {open && (
        <div
          id="mobile-nav"
          className="border-t border-white/[0.08] bg-ink-900/95 px-4 py-5 backdrop-blur-xl lg:hidden"
        >
          <ul className="flex flex-col gap-3 text-sm font-medium text-slate-200">
            {LINKS.map((l) => (
              <li key={l.href}>
                <a
                  href={l.href}
                  onClick={() => setOpen(false)}
                  className="block rounded-lg px-2 py-1.5 hover:bg-white/5 hover:text-white"
                >
                  {l.label}
                </a>
              </li>
            ))}
          </ul>
          <div className="mt-4 flex flex-col gap-2.5 border-t border-white/10 pt-4">
            <a
              href="#contact"
              onClick={() => setOpen(false)}
              className="inline-flex items-center justify-center gap-1.5 rounded-xl bg-gradient-to-r from-brand-500 to-cyan-400 px-4 py-2.5 text-sm font-semibold text-ink-950"
            >
              Get a Proposal
              <ArrowUpRight className="h-4 w-4" />
            </a>
          </div>
        </div>
      )}
    </header>
  );
}
