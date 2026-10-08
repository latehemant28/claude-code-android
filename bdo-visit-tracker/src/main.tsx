import React from 'react';
import ReactDOM from 'react-dom/client';
import { registerSW } from 'virtual:pwa-register';
import App from './App';
import { isNative } from './lib/platform';
import './index.css';

// Native apps ship their files inside the app; only the web version needs the offline service worker.
if (!isNative()) registerSW({ immediate: true });

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
