import { useState } from 'react';
import { Navbar } from './components/Navbar';
import { Hero } from './components/Hero';
import { Services } from './components/Services';
import { PeerSplitSpotlight } from './components/PeerSplitSpotlight';
import { Portfolio } from './components/Portfolio';
import { Process } from './components/Process';
import { Estimator, type EstimatorPrefill } from './components/Estimator';
import { ContactForm } from './components/ContactForm';
import { Footer } from './components/Footer';

export default function App() {
  const [prefill, setPrefill] = useState<EstimatorPrefill | null>(null);

  const handleApplyEstimate = (data: EstimatorPrefill) => {
    setPrefill({ ...data });
    const contactEl = document.getElementById('contact');
    contactEl?.scrollIntoView({ behavior: 'smooth' });
  };

  return (
    <div className="min-h-screen bg-ink-950 text-slate-100 selection:bg-brand-500 selection:text-white">
      <Navbar />
      <main>
        <Hero />
        <Services />
        <PeerSplitSpotlight />
        <Portfolio />
        <Process />
        <Estimator onApplyToForm={handleApplyEstimate} />
        <ContactForm prefill={prefill} />
      </main>
      <Footer />
    </div>
  );
}
