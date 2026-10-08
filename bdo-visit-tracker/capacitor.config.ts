import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.bdovisittracker.app',
  appName: 'BDO Visit Tracker',
  webDir: 'dist',
  android: {
    // Never let other apps or a USB debugger inspect the web view in release builds.
    webContentsDebuggingEnabled: false,
  },
  ios: {
    contentInset: 'never',
  },
};

export default config;
