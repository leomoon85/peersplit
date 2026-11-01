import { useState } from 'react';
import { Navbar } from './components/Navbar';
import { Hero } from './components/Hero';
import { Services } from './components/Services';
import { PeerSplitSpotlight } from './components/PeerSplitSpotlight';
import { Portfolio } from './components/Portfolio';
import { Process } from './components/Process';
import { Estimator, type ScopePrefill } from './components/Estimator';
import { ContactForm } from './components/ContactForm';
import { Footer } from './components/Footer';

export default function App() {
  const [prefill, setPrefill] = useState<ScopePrefill | null>(null);

  const handleApplyScope = (data: ScopePrefill) => {
    setPrefill({ ...data });
    const contactEl = document.getElementById('contact');
    contactEl?.scrollIntoView({ behavior: 'smooth' });
  };

  return (
    <div className="min-h-screen bg-slate-50 text-slate-800 selection:bg-blue-600 selection:text-white">
      <Navbar />
      <main>
        <Hero />
        <Services />
        <PeerSplitSpotlight />
        <Portfolio />
        <Process />
        <Estimator onApplyToForm={handleApplyScope} />
        <ContactForm prefill={prefill} />
      </main>
      <Footer />
    </div>
  );
}
