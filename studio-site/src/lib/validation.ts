export interface LeadForm {
  fullName: string;
  businessName: string;
  email: string;
  phone: string;
  service: string;
  budget: string;
  details: string;
  website: string; // honeypot — must stay empty
}

export type FormErrors = Partial<Record<keyof LeadForm, string>>;

export const EMPTY_LEAD: LeadForm = {
  fullName: '',
  businessName: '',
  email: '',
  phone: '',
  service: '',
  budget: '',
  details: '',
  website: '',
};

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/;
const PHONE_RE = /^\+?[0-9][0-9\s-]{7,15}$/;

/** Step 0 = contact details, step 1 = project details. Step 2 (review) validates everything. */
export function validateStep(form: LeadForm, step: number): FormErrors {
  const e: FormErrors = {};
  if (step === 0 || step >= 2) {
    if (form.fullName.trim().length < 2) e.fullName = 'Please enter your full name.';
    if (form.businessName.trim().length < 2) e.businessName = 'Please enter your business name.';
    if (!EMAIL_RE.test(form.email.trim())) e.email = 'Please enter a valid email address.';
    if (form.phone.trim() && !PHONE_RE.test(form.phone.trim())) e.phone = 'Please enter a valid phone number.';
  }
  if (step === 1 || step >= 2) {
    if (!form.service) e.service = 'Please choose a service.';
    if (!form.budget) e.budget = 'Please choose a budget range.';
    if (form.details.trim().length < 20) e.details = 'Tell us a little more (at least 20 characters).';
    if (form.details.length > 4000) e.details = 'Please keep project details under 4,000 characters.';
  }
  return e;
}
