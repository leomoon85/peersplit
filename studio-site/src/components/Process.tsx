import { PROCESS } from '../data/content';

export function Process() {
  return (
    <section
      id="process"
      aria-labelledby="process-heading"
      className="border-b border-slate-200 bg-slate-50/70 py-20 sm:py-28"
    >
      <div className="mx-auto max-w-7xl px-4 sm:px-6 lg:px-8">
        <div className="max-w-2xl">
          <span className="font-mono text-xs font-semibold uppercase tracking-wider text-blue-600">
            Transparent Engagement Model
          </span>
          <h2
            id="process-heading"
            className="mt-2 text-3xl font-extrabold tracking-tight text-slate-900 sm:text-4xl"
          >
            From Workflow Audit to Production Launch in 4 Steps
          </h2>
          <p className="mt-3 text-base text-slate-600">
            No black-box development. You review an interactive prototype in week one, receive
            installable builds at every sprint, and sign off at each milestone.
          </p>
        </div>

        <ol className="mt-12 grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
          {PROCESS.map((step, index) => (
            <li
              key={step.title}
              className="card-base card-hover relative flex flex-col justify-between p-6"
            >
              <div>
                <div className="flex items-center justify-between">
                  <span className="inline-flex h-10 w-10 items-center justify-center rounded-xl bg-blue-600 font-mono text-sm font-bold text-white shadow-sm">
                    0{index + 1}
                  </span>
                  <span className="rounded-full border border-slate-200 bg-slate-100 px-3 py-1 font-mono text-xs font-semibold text-slate-700">
                    {step.duration}
                  </span>
                </div>

                <h3 className="mt-5 text-lg font-bold text-slate-900">
                  Step {index + 1}: {step.title}
                </h3>
                <p className="mt-2 text-sm leading-relaxed text-slate-600">{step.body}</p>
              </div>

              <div className="mt-6 border-t border-slate-100 pt-3 font-mono text-[11px] font-semibold uppercase tracking-wider text-slate-600">
                Milestone Sign-Off Included
              </div>
            </li>
          ))}
        </ol>
      </div>
    </section>
  );
}
