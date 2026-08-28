import { useEffect, useState } from "react";
import { WifiOff } from "lucide-react";
import { listDeviceApps, setVpnAllowlist, type DeviceApp } from "@/lib/native";
import { useSecurity } from "@/lib/security/store";
import { Panel, PanelHeader } from "./chrome";

const KEY = "kysmindset-vpn-allow";

function loadAllow(): DeviceApp[] {
  try {
    const raw = localStorage.getItem(KEY);
    const parsed = raw ? (JSON.parse(raw) as DeviceApp[]) : [];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function saveAllow(list: DeviceApp[]) {
  try {
    localStorage.setItem(KEY, JSON.stringify(list));
  } catch {
    /* ignore */
  }
  setVpnAllowlist(list.map((a) => a.pkg));
}

export function KillAllowPanel() {
  const killSwitch = useSecurity((s) => s.killSwitch);
  const [apps, setApps] = useState<DeviceApp[]>([]);
  const [vpnAllow, setVpnAllow] = useState<DeviceApp[]>([]);
  const [q, setQ] = useState("");
  useEffect(() => {
    setApps(listDeviceApps());
    const saved = loadAllow();
    setVpnAllow(saved);
    setVpnAllowlist(saved.map((a) => a.pkg));
  }, []);
  const toggle = (app: DeviceApp) => {
    const on = vpnAllow.some((a) => a.pkg === app.pkg);
    const next = on ? vpnAllow.filter((a) => a.pkg !== app.pkg) : [...vpnAllow, app];
    setVpnAllow(next);
    saveAllow(next);
  };
  const selected = new Set(vpnAllow.map((a) => a.pkg));
  const filtered = apps.filter((a) => {
    const s = q.trim().toLowerCase();
    if (!s) return true;
    return a.name.toLowerCase().includes(s) || a.pkg.toLowerCase().includes(s);
  });
  return (
    <Panel>
      <PanelHeader
        icon={<WifiOff className="size-4" />}
        title="Kill switch allowlist"
        subtitle="These apps keep internet while Kill is on"
        iconClass="bg-emerald-dim text-emerald"
      />
      <p className="mb-3 text-micro text-muted">
        You pick which apps stay online. Everyone else is cut when Kill is armed.
        {killSwitch ? " Changes apply immediately." : " Pick apps first, then arm Kill."}
      </p>
      {apps.length === 0 ? (
        <p className="rounded-md border border-line bg-elevated px-3 py-3 text-xs text-subtle">
          Installed apps appear here on the Android APK.
        </p>
      ) : (
        <>
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Search apps…"
            className="mb-2 w-full rounded-sm border border-line bg-elevated px-3 py-2 text-xs text-fg outline-none placeholder:text-subtle focus:border-cyan/50"
          />
          <p className="mb-2 text-2xs text-subtle">
            {selected.size} allowed · {filtered.length} shown
          </p>
          <div className="max-h-72 space-y-1 overflow-y-auto pr-1">
            {filtered.map((a) => {
              const on = selected.has(a.pkg);
              return (
                <button
                  key={a.pkg}
                  type="button"
                  onClick={() => toggle(a)}
                  className="flex w-full items-center gap-3 rounded-sm border border-line bg-elevated px-3 py-2 text-left"
                >
                  <span
                    className={
                      on
                        ? "flex size-5 items-center justify-center rounded-sm bg-cyan text-2xs text-bg"
                        : "size-5 rounded-sm border border-line-strong"
                    }
                  >
                    {on ? "✓" : ""}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm text-fg">{a.name}</span>
                    <span className="block truncate font-mono text-2xs text-subtle">{a.pkg}</span>
                  </span>
                </button>
              );
            })}
          </div>
        </>
      )}
      {vpnAllow.length > 0 ? (
        <div className="mt-3 flex flex-wrap gap-1.5">
          {vpnAllow.map((a) => (
            <button
              key={a.pkg}
              type="button"
              onClick={() => toggle(a)}
              className="rounded-full border border-cyan/30 bg-cyan-dim px-2 py-0.5 text-2xs text-cyan"
            >
              {a.name} ×
            </button>
          ))}
        </div>
      ) : null}
    </Panel>
  );
}
