import { listDeviceApps, setVpnAllowlist, type DeviceApp } from "./native";

const VPN_ALLOW_KEY = "kysmindset-vpn-allow";

const APP_ALIASES: { keys: string[]; name: string; pkgs: string[] }[] = [
  { keys: ["facebook", "fb"], name: "Facebook", pkgs: ["com.facebook.katana", "com.facebook.lite"] },
  { keys: ["messenger"], name: "Messenger", pkgs: ["com.facebook.orca"] },
  { keys: ["instagram", "ig"], name: "Instagram", pkgs: ["com.instagram.android"] },
  { keys: ["whatsapp"], name: "WhatsApp", pkgs: ["com.whatsapp", "com.whatsapp.w4b"] },
  { keys: ["youtube"], name: "YouTube", pkgs: ["com.google.android.youtube"] },
  { keys: ["gmail"], name: "Gmail", pkgs: ["com.google.android.gm"] },
  { keys: ["maps"], name: "Maps", pkgs: ["com.google.android.apps.maps"] },
  { keys: ["chrome"], name: "Chrome", pkgs: ["com.android.chrome"] },
  { keys: ["messages", "sms"], name: "Messages", pkgs: ["com.google.android.apps.messaging"] },
  { keys: ["phone", "dialer"], name: "Phone", pkgs: ["com.google.android.dialer", "com.samsung.android.dialer"] },
  { keys: ["twitter", "x"], name: "X", pkgs: ["com.twitter.android"] },
  { keys: ["tiktok"], name: "TikTok", pkgs: ["com.zhiliaoapp.musically"] },
  { keys: ["snapchat", "snap"], name: "Snapchat", pkgs: ["com.snapchat.android"] },
  { keys: ["spotify"], name: "Spotify", pkgs: ["com.spotify.music"] },
];

export function loadVpnAllow(): DeviceApp[] {
  try {
    const raw = localStorage.getItem(VPN_ALLOW_KEY);
    const parsed = raw ? (JSON.parse(raw) as DeviceApp[]) : [];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

export function saveVpnAllow(list: DeviceApp[]) {
  const uniq: DeviceApp[] = [];
  const seen = new Set<string>();
  for (const a of list) {
    if (!a.pkg || seen.has(a.pkg)) continue;
    seen.add(a.pkg);
    uniq.push(a);
  }
  try {
    localStorage.setItem(VPN_ALLOW_KEY, JSON.stringify(uniq));
  } catch {
    /* ignore */
  }
  setVpnAllowlist(uniq.map((a) => a.pkg));
  return uniq;
}

export function resolveTypedApp(query: string): DeviceApp[] {
  const q = query.trim().toLowerCase();
  if (!q) return [];
  const installed = listDeviceApps();
  const hits: DeviceApp[] = [];
  for (const a of installed) {
    if (a.name.toLowerCase().includes(q) || a.pkg.toLowerCase().includes(q)) hits.push(a);
  }
  for (const alias of APP_ALIASES) {
    if (!alias.keys.some((k) => q === k || q.startsWith(k) || k.startsWith(q))) continue;
    const onPhone = installed.filter(
      (a) => alias.pkgs.includes(a.pkg) || a.name.toLowerCase().includes(alias.keys[0]),
    );
    if (onPhone.length) hits.push(...onPhone);
    else hits.push({ name: alias.name, pkg: alias.pkgs[0] });
  }
  const uniq: DeviceApp[] = [];
  const seen = new Set<string>();
  for (const h of hits) {
    if (seen.has(h.pkg)) continue;
    seen.add(h.pkg);
    uniq.push(h);
  }
  return uniq;
}

export function addTypedVpnAllow(query: string): DeviceApp[] {
  return saveVpnAllow([...loadVpnAllow(), ...resolveTypedApp(query)]);
}
