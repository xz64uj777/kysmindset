import { useEffect, useState } from "react";
import { Plus, WifiOff } from "lucide-react";
import { listDeviceApps, type DeviceApp } from "@/lib/native";
import { addTypedVpnAllow, loadVpnAllow, saveVpnAllow } from "@/lib/vpn-allow";
import { useSecurity } from "@/lib/security/store";
import { Panel, PanelHeader } from "./chrome";
import { toast } from "sonner";

export function KillAllowPanel() {
  const killSwitch = useSecurity((s) => s.killSwitch);
  const [apps, setApps] = useState<DeviceApp[]>([]);
  const [vpnAllow, setVpnAllow] = useState<DeviceApp[]>([]);
  const [q, setQ] = useState("");
  const [typed, setTyped] = useState("");
  useEffect(() => {
    setApps(listDeviceApps());
    const saved = loadVpnAllow();
    setVpnAllow(saved);
    saveVpnAllow(saved);
  }, []);
  const apply = (next: DeviceApp[]) => {
    setVpnAllow(next);
    saveVpnAllow(next);
  };
  const toggle = (app: DeviceApp) => {
    const on = vpnAllow.some((a) => a.pkg === app.pkg);
    apply(on ? vpnAllow.filter((a) => a.pkg !== app.pkg) : [...vpnAllow, app]);
  };
  const addTyped = () => {
    const query = typed.trim();
    if (!query) return;
    const next = addTypedVpnAllow(query);
    setVpnAllow(next);
    setApps(listDeviceApps());
    const added = next.filter((a) => !vpnAllow.some((b) => b.pkg === a.pkg));
    if (added.length) toast.success(`Allowed ${added.map((a) => a.name).join(", ")}`);
    else toast.error(`No app matched "${query}"`);
    setTyped("");
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
        title="Do not block"
        subtitle="These apps keep internet while Kill is on"
        iconClass="bg-emerald-dim text-emerald"
      />
      <p className="mb-3 text-micro text-muted">
        Type a name (Facebook, Maps, Messages) or tap an installed app.
        {killSwitch ? " Changes apply immediately." : " Arm Kill after you pick apps."}
      </p>
      <form
        className="mb-3 flex gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          addTyped();
        }}
      >
        <input
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          placeholder="Type Facebook, Maps..."
          className="flex-1 rounded-sm border border-line bg-elevated px-3 py-2 text-xs text-fg outline-none placeholder:text-subtle focus:border-cyan/50"
        />
        <button type="submit" className="rounded-sm bg-cyan-dim px-3 py-2 text-cyan hover:bg-cyan/20">
          <Plus className="size-4" />
        </button>
      </form>
      {apps.length === 0 ? (
        <p className="rounded-md border border-line bg-elevated px-3 py-3 text-xs text-subtle">
          Installed-app list needs the APK. Typing a name still maps common apps.
        </p>
      ) : (
        <>
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Filter installed apps..."
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
