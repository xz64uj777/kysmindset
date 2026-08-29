export type OwnerStatus = {
  android: boolean;
  owner: boolean;
  admin: boolean;
  lockTask: boolean;
  keyguardOff: boolean;
  replaceKeyguard: boolean;
  adb: string;
  component: string;
};

type LockBridge = {
  setGate?: (locked: boolean) => void;
  setDeviceLock?: (on: boolean) => void;
  ownerStatus?: () => string;
  requestAdmin?: () => void;
  applyOwner?: (replaceKeyguard: boolean) => string;
  lockNow?: () => void;
  removeAdmin?: () => string;
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

export function readOwnerStatus(): OwnerStatus | null {
  const b = bridge();
  if (!b?.ownerStatus) return null;
  try {
    const parsed = JSON.parse(b.ownerStatus()) as OwnerStatus;
    return parsed && typeof parsed === "object" ? parsed : null;
  } catch {
    return null;
  }
}

export function requestAndroidAdmin() {
  try {
    bridge()?.requestAdmin?.();
  } catch {
    /* ignore */
  }
}

export function applyAndroidOwner(replaceKeyguard: boolean): OwnerStatus | null {
  const b = bridge();
  if (!b?.applyOwner) return readOwnerStatus();
  try {
    const parsed = JSON.parse(b.applyOwner(replaceKeyguard)) as OwnerStatus;
    return parsed && typeof parsed === "object" ? parsed : readOwnerStatus();
  } catch {
    return readOwnerStatus();
  }
}

export function androidLockNow() {
  try {
    bridge()?.lockNow?.();
  } catch {
    /* ignore */
  }
}

export function removeAndroidAdmin(): OwnerStatus | null {
  const b = bridge();
  if (!b?.removeAdmin) return readOwnerStatus();
  try {
    const parsed = JSON.parse(b.removeAdmin()) as OwnerStatus;
    return parsed && typeof parsed === "object" ? parsed : readOwnerStatus();
  } catch {
    return readOwnerStatus();
  }
}
