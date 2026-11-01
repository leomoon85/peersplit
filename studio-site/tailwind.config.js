/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['"Plus Jakarta Sans"', 'system-ui', 'sans-serif'],
        display: ['"Space Grotesk"', 'system-ui', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'monospace'],
      },
      colors: {
        obsidian: '#05070d',
        night: '#090d16',
        panel: '#0f1624',
      },
      boxShadow: {
        'glow-emerald': '0 0 70px -15px rgba(16,185,129,0.25)',
        'glow-amber': '0 0 70px -15px rgba(245,158,11,0.25)',
      },
    },
  },
  plugins: [],
};
