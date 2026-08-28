type LockBridge = {
  setGate?: (locked: boolean) => void;
  setDeviceLock?: (on: boolean) => void;
};

function bridge(): LockBridge | null {
  return (window as unknown as { KysAndroid?: LockBridge }).KysAndroid ?? null;
}

export function isAndroidApp() {
  return bridge() != null;
}

export function setAndroidGate(locked: boolean) {
  try {
    bridge()?.setGate?.(locked);
  } catch {
    /* ignore */
  }
}

export function setAndroidDeviceLock(on: boolean) {
  try {
    bridge()?.setDeviceLock?.(on);
  } catch {
    /* ignore */
  }
}
