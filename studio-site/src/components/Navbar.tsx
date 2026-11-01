import { useState } from 'react';
import { ArrowUpRight, Menu, X } from 'lucide-react';

const LINKS = [
  { href: '#services', label: 'Services' },
  { href: '#portfolio', label: 'Our Work' },
  { href: '#products', label: 'Case Study (PeerSplit)' },
  { href: '#process', label: 'How We Work' },
  { href: '#planner', label: 'Scope Planner' },
];

export function Navbar() {
  const [open, setOpen] = useState(false);

  return (
    <header className="sticky top-0 z-50 border-b border-slate-200 bg-white/90 backdrop-blur-md">
      <nav
        aria-label="Primary"
        className="mx-auto flex max-w-7xl items-center justify-between px-4 py-3.5 sm:px-6 lg:px-8"
      >
        <a href="#top" className="flex items-center gap-3 group">
          <span className="relative grid h-10 w-10 place-items-center overflow-hidden rounded-xl border border-slate-200 bg-slate-50 shadow-sm transition group-hover:border-blue-300">
            <img
              src="/assets/peerloom_brand_logo.png"
              alt="Peerloom Labs emblem"
              className="h-8 w-8 object-contain"
              onError={(e) => {
                (e.currentTarget as HTMLImageElement).style.display = 'none';
              }}
            />
          </span>
          <span className="flex flex-col leading-tight">
            <span className="text-base font-extrabold tracking-tight text-slate-900">
              Peerloom <span className="text-blue-600">Labs</span>
            </span>
            <span className="font-mono text-[10px] uppercase tracking-wider text-slate-600">
              Software Studio · B2B IT Solutions
            </span>
          </span>
        </a>

        <ul className="hidden items-center gap-7 text-sm font-medium text-slate-600 lg:flex">
          {LINKS.map((l) => (
            <li key={l.href}>
              <a
                href={l.href}
                className="transition-colors hover:text-blue-600"
              >
                {l.label}
              </a>
            </li>
          ))}
        </ul>

        <div className="hidden items-center gap-3 lg:flex">
          <a
            href="#planner"
            className="rounded-xl border border-slate-200 bg-slate-50 px-3.5 py-2 text-xs font-semibold text-slate-700 transition hover:bg-slate-100 hover:text-slate-900"
          >
            Scope Planner
          </a>
          <a
            href="#contact"
            className="btn-primary"
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
          className="inline-flex items-center justify-center rounded-xl border border-slate-200 bg-white p-2 text-slate-700 hover:bg-slate-100 lg:hidden"
        >
          {open ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
        </button>
      </nav>

      {open && (
        <div
          id="mobile-nav"
          className="border-t border-slate-200 bg-white px-4 py-5 shadow-lg lg:hidden"
        >
          <ul className="flex flex-col gap-3 text-sm font-medium text-slate-700">
            {LINKS.map((l) => (
              <li key={l.href}>
                <a
                  href={l.href}
                  onClick={() => setOpen(false)}
                  className="block rounded-lg px-2 py-1.5 hover:bg-slate-50 hover:text-blue-600"
                >
                  {l.label}
                </a>
              </li>
            ))}
          </ul>
          <div className="mt-4 border-t border-slate-200 pt-4">
            <a
              href="#contact"
              onClick={() => setOpen(false)}
              className="btn-primary w-full"
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
