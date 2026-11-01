import { PROCESS } from '../data/content';

export function Process() {
  return (
    <section
      id="process"
      aria-labelledby="process-heading"
      className="border-b border-white/[0.07] py-20 sm:py-28"
    >
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="max-w-2xl">
          <span className="font-mono text-xs font-semibold uppercase tracking-[0.2em] text-cyan-400">
            How We Work
          </span>
          <h2
            id="process-heading"
            className="mt-3 text-3xl font-extrabold tracking-tight text-white sm:text-4xl"
          >
            From Workflow Audit to Play Store Launch in 4 Clear Steps
          </h2>
          <p className="mt-3 text-base text-slate-300">
            No black-box outsourcing. You see an interactive prototype in week one and receive
            installable builds on your phone at the end of every sprint.
          </p>
        </div>

        <ol className="mt-12 grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
          {PROCESS.map((step, index) => (
            <li
              key={step.title}
              className="glass relative flex flex-col justify-between rounded-2xl p-6"
            >
              <div>
                <div className="flex items-center justify-between">
                  <span className="inline-flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-br from-brand-500 to-cyan-400 font-mono text-sm font-extrabold text-ink-950 shadow-glow">
                    0{index + 1}
                  </span>
                  <span className="rounded-full border border-white/10 bg-white/[0.04] px-3 py-1 font-mono text-xs text-cyan-300">
                    {step.duration}
                  </span>
                </div>

                <h3 className="mt-5 text-lg font-bold text-white">
                  Step {index + 1}: {step.title}
                </h3>
                <p className="mt-2.5 text-sm leading-relaxed text-slate-300">{step.body}</p>
              </div>

              <div className="mt-6 border-t border-white/10 pt-3 font-mono text-[11px] uppercase tracking-wider text-slate-400">
                Milestone Sign-Off Included
              </div>
            </li>
          ))}
        </ol>
      </div>
    </section>
  );
}
