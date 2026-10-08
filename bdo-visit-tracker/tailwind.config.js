/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        brand: { 50: '#eef4fb', 100: '#d6e4f4', 600: '#164f8a', 700: '#0f4478', 800: '#0b3d6e', 900: '#082c50' },
      },
    },
  },
  plugins: [],
};
