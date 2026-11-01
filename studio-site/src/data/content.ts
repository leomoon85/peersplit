import { LifeBuoy, QrCode, Smartphone, Workflow, type LucideIcon } from 'lucide-react';

export interface Service {
  id: string;
  icon: LucideIcon;
  title: string;
  summary: string;
  bullets: string[];
}

export const SERVICES: Service[] = [
  {
    id: 'mobile',
    icon: Smartphone,
    title: 'Custom Mobile App Development',
    summary: 'Native-feel iOS & Android apps built with Flutter or React Native from one codebase.',
    bullets: ['Customer & staff apps', 'Offline-first data sync', 'Play Store & App Store publishing'],
  },
  {
    id: 'automation',
    icon: Workflow,
    title: 'Business Automation & Web Portals',
    summary: 'Replace spreadsheets and paper registers with tools built around how you actually work.',
    bullets: ['Party ledgers & khata books', 'GST-ready billing & invoicing', 'Inventory & order tracking'],
  },
  {
    id: 'upi',
    icon: QrCode,
    title: 'UPI & Payment Gateways',
    summary: 'Collect payments faster with UPI flows that reconcile automatically against your records.',
    bullets: ['Dynamic UPI QR codes', 'App-to-app intent payments', 'Gateway & settlement reconciliation'],
  },
  {
    id: 'support',
    icon: LifeBuoy,
    title: 'Maintenance & Managed Cloud',
    summary: 'We keep your software secure, current and online after launch so you can run the business.',
    bullets: ['Store releases & OS updates', 'Server monitoring & backups', 'Priority bug fixes'],
  },
];

export const PROCESS = [
  {
    title: 'Discovery & Workflow Audit',
    body: 'A free call to map your current process, pain points and goals. You get a written scope and fixed quote.',
    duration: 'Week 0',
  },
  {
    title: 'Interactive 2-Minute Prototype',
    body: 'A clickable prototype of the key screens so you can feel the product before a single line of production code.',
    duration: 'Week 1',
  },
  {
    title: 'Sprint Build & QA Testing',
    body: 'Two-week sprints with a demo build on your phone at the end of each one. Every release is tested on real devices.',
    duration: 'Weeks 2–N',
  },
  {
    title: 'Deployment & Retainer Support',
    body: 'We publish to Google Play, the App Store or the web, hand over credentials, and stay on call through a retainer.',
    duration: 'Launch',
  },
];

export const BUDGETS = [
  { value: '<1L', label: 'Under ₹1 Lakh' },
  { value: '1-3L', label: '₹1 – 3 Lakh' },
  { value: '3-6L', label: '₹3 – 6 Lakh' },
  { value: '6L+', label: '₹6 Lakh +' },
  { value: 'unsure', label: 'Not sure yet' },
];
