import { useEffect, useMemo, useState } from "react";
import { Globe, Network } from "lucide-react";
import { Area, AreaChart, ResponsiveContainer } from "recharts";
import { toast } from "sonner";
import { groupByDomain } from "@/lib/security/domain";
import { useSecurity } from "@/lib/security/store";
import { hasAndroidBridge, readLiveNet, setConnBlocked, type LiveConn } from "@/lib/native";
import { cn, formatKb } from "@/lib/utils";
import { GroupedActivityList } from "./grouped-list";
import { Panel, PanelHeader } from "./chrome";
import { Button } from "@/components/ui/button";

export function NetworkPanel() {
  if (hasAndroidBridge()) return <PhoneNetwork />;
  return <WebNetwork />;
}

function PhoneNetwork() {
  const killSwitch = useSecurity((s) => s.killSwitch);
  const toggleKillSwitch = useSecurity((s) => s.toggleKillSwitch);
  const [q, setQ] = useState("");
  const [onlyBlocked, setOnlyBlocked] = useState(false);
  const [net, setNet] = useState(() => readLiveNet());

  useEffect(() => {
    const tick = () => setNet(readLiveNet());
    tick();
    const id = window.setInterval(tick, 1000);
    return () => window.clearInterval(id);
  }, [killSwitch]);

  const rows = net?.rows ?? [];
  const groups = useMemo(() => {
    const map = new Map<string, { pkg: string; name: string; blocked: boolean; items: LiveConn[] }>();
    for (const r of rows) {
      const key = r.pkg || r.name;
      let g = map.get(key);
      if (!g) {
        g = { pkg: r.pkg, name: r.name || r.pkg || "Unknown", blocked: r.blocked, items: [] };
        map.set(key, g);
      }
      if (r.blocked) g.blocked = true;
      g.items.push(r);
    }
    const s = q.trim().toLowerCase();
    return [...map.values()]
      .filter((g) => {
        if (onlyBlocked && !g.blocked) return false;
        if (!s) return true;
        if (g.name.toLowerCase().includes(s) || g.pkg.toLowerCase().includes(s)) return true;
        return g.items.some(
          (i) =>
            i.host.toLowerCase().includes(s) ||
            i.ip.toLowerCase().includes(s) ||
            String(i.port).includes(s),
        );
      })
      .sort((a, b) => Math.max(...b.items.map((i) => i.lastAt)) - Math.max(...a.items.map((i) => i.lastAt)));
  }, [rows, q, onlyBlocked]);

  return (
    <div className="space-y-4">
      <Panel>
        <PanelHeader
          icon={<Network className="size-4" />}
          title="Live connections"
          subtitle={
            killSwitch
              ? "Every app that talks on the internet, as it happens"
              : "Start Protection to watch the phone — not just this page"
          }
        />
        <div className="grid grid-cols-3 gap-2 text-center">
          <Stat label="Apps" value={String(groups.length)} />
          <Stat label="Sockets" value={String(rows.length)} />
          <Stat label="Cut" value={String(net?.drops ?? 0)} hot={(net?.drops ?? 0) > 0} />
        </div>
        {!killSwitch ? (
          <div className="mt-3 rounded-md border border-amber/20 bg-amber-dim px-3 py-2">
            <p className="text-xs text-amber">Protection is off. Nothing on the phone is being watched.</p>
            <Button
              size="sm"
              className="mt-2"
              onClick={() => {
                toggleKillSwitch();
                toast.message("Allow the VPN screen if Android shows it.");
              }}
            >
              Start Protection
            </Button>
          </div>
        ) : (
          <p className="mt-3 text-micro text-muted">
            Internet still works. Tap Block on an app to cut it. Always-allow list is in Config.
          </p>
        )}
      </Panel>
      <Panel>
        <PanelHeader icon={<Globe className="size-4" />} title="By app" subtitle="Newest first" />
        <div className="mb-3 flex flex-wrap gap-2">
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Find app or host"
            className="min-w-0 flex-1 rounded-md border border-line bg-elevated px-3 py-1.5 text-sm text-fg"
          />
          <button
            type="button"
            onClick={() => setOnlyBlocked((v) => !v)}
            className={cn(
              "rounded-full border px-3 py-1 text-xs",
              onlyBlocked ? "border-red/40 bg-red-dim text-red" : "border-line text-muted",
            )}
          >
            Blocked
          </button>
        </div>
        {groups.length === 0 ? (
          <p className="py-8 text-center text-xs text-subtle">
            {killSwitch ? "Waiting for an app to go online…" : "Start Protection, then open Chrome or Messages."}
          </p>
        ) : (
          <div className="space-y-2">
            {groups.map((g) => (
              <div key={g.pkg || g.name} className="rounded-md border border-line bg-elevated p-3">
                <div className="flex items-start gap-2">
                  <div className="min-w-0 flex-1">
                    <div className="text-sm font-medium text-fg">{g.name}</div>
                    <div className="truncate text-2xs text-subtle">{g.pkg}</div>
                  </div>
                  {g.pkg ? (
                    <button
                      type="button"
                      onClick={() => {
                        const next = !g.blocked;
                        setConnBlocked(g.pkg, next);
                        setNet(readLiveNet());
                        toast.message(next ? `${g.name} blocked` : `${g.name} allowed`);
                      }}
                      className={cn(
                        "shrink-0 rounded-md border px-2 py-1 text-xs font-medium",
                        g.blocked
                          ? "border-red/40 bg-red-dim text-red"
                          : "border-line text-muted hover:bg-white/5 hover:text-fg",
                      )}
                    >
                      {g.blocked ? "Blocked" : "Block"}
                    </button>
                  ) : null}
                </div>
                <ul className="mt-2 space-y-1">
                  {g.items.slice(0, 8).map((i) => (
                    <li key={`${i.proto}-${i.ip}-${i.port}-${i.lastAt}`} className="flex justify-between gap-2 text-2xs">
                      <span className="min-w-0 truncate text-muted">
                        <span className="text-subtle">{i.proto}</span>{" "}
                        {i.host || i.ip}:{i.port}
                      </span>
                      <span className="shrink-0 font-mono text-subtle">{formatKb(i.bytes / 1024)}</span>
                    </li>
                  ))}
                  {g.items.length > 8 ? (
                    <li className="text-2xs text-subtle">+{g.items.length - 8} more</li>
                  ) : null}
                </ul>
              </div>
            ))}
          </div>
        )}
      </Panel>
    </div>
  );
}

function Stat({ label, value, hot }: { label: string; value: string; hot?: boolean }) {
  return (
    <div className="rounded-md border border-line bg-elevated p-2.5">
      <div className="text-2xs text-subtle">{label}</div>
      <div className={cn("text-sm font-semibold", hot ? "text-red" : "text-fg")}>{value}</div>
    </div>
  );
}

function WebNetwork() {
  const activities = useSecurity((s) => s.activities);
  const killSwitch = useSecurity((s) => s.killSwitch);
  const droppedPackets = useSecurity((s) => s.droppedPackets);
  const linkKbps = useSecurity((s) => s.linkKbps);
  const toggleKillSwitch = useSecurity((s) => s.toggleKillSwitch);
  const [filter, setFilter] = useState<"all" | "open" | "inbound" | "outbound" | "dropped">("all");
  const [spark, setSpark] = useState<{ t: string; kbps: number }[]>(() =>
    Array.from({ length: 16 }, (_, i) => ({ t: String(i), kbps: 0 })),
  );

  useEffect(() => {
    setSpark((prev) => [...prev.slice(1), { t: String(Date.now()).slice(-4), kbps: killSwitch ? 0 : linkKbps }]);
  }, [linkKbps, killSwitch]);

  const traffic = activities.filter((a) => a.type === "traffic");
  const items = traffic.filter((a) => {
    if (filter === "dropped") return a.resolveNote === "Network kill switch" || a.status === "blocked";
    if (filter === "open") return a.status === "suspicious" || a.status === "unknown";
    if (filter === "all") return true;
    return a.direction === filter;
  });
  const inbound = traffic.filter((a) => a.direction === "inbound").length;
  const outbound = traffic.filter((a) => a.direction === "outbound").length;
  const dropped = traffic.filter((a) => a.status === "blocked" || a.status === "killed").length;
  const dirTotal = Math.max(1, inbound + outbound + dropped);
  const domains = groupByDomain(traffic)
    .map((g) => ({
      label: g.label,
      kb: g.items.reduce((n, i) => n + (i.dataKb ?? 0), 0),
    }))
    .sort((a, b) => b.kb - a.kb)
    .slice(0, 8);
  const maxKb = Math.max(1, ...domains.map((d) => d.kb));

  return (
    <div className="space-y-4">
      <Panel>
        <PanelHeader icon={<Network className="size-4" />} title="Live path" subtitle="This page only — install the APK to watch the phone" />
        <div className="h-28 -ml-2">
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={spark}>
              <defs>
                <linearGradient id="gKbps" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor={killSwitch ? "#f87171" : "#22d3ee"} stopOpacity={0.4} />
                  <stop offset="100%" stopColor={killSwitch ? "#f87171" : "#22d3ee"} stopOpacity={0} />
                </linearGradient>
              </defs>
              <Area
                type="monotone"
                dataKey="kbps"
                stroke={killSwitch ? "#f87171" : "#22d3ee"}
                fill="url(#gKbps)"
                strokeWidth={1.5}
                isAnimationActive={false}
              />
            </AreaChart>
          </ResponsiveContainer>
        </div>
        <div className="mt-1 flex items-baseline justify-between text-micro text-muted">
          <span className={cn("font-mono text-sm tabular-nums", killSwitch ? "text-red" : "text-fg")}>
            {killSwitch ? 0 : linkKbps} KB/s
          </span>
          <span>{droppedPackets.toLocaleString()} drops</span>
        </div>
        <div className="mt-3 space-y-1.5">
          <DirBar label="Out" value={outbound} total={dirTotal} tone="bg-cyan" />
          <DirBar label="In" value={inbound} total={dirTotal} tone="bg-emerald" />
          <DirBar label="Cut" value={dropped} total={dirTotal} tone="bg-red" />
        </div>
        {killSwitch ? (
          <div className="mt-3 flex flex-wrap items-center justify-between gap-2 rounded-md border border-red/20 bg-red-dim px-3 py-2">
            <p className="text-xs text-red">This page’s third-party fetches are blocked.</p>
            <button
              type="button"
              onClick={() => {
                toggleKillSwitch();
                toast.success("Protection off.");
              }}
              className="shrink-0 text-xs font-medium text-fg underline-offset-2 hover:underline"
            >
              Release
            </button>
          </div>
        ) : null}
      </Panel>
      {domains.length > 0 ? (
        <Panel>
          <PanelHeader icon={<Globe className="size-4" />} title="Hosts this session" subtitle="This page only" />
          <div className="space-y-2">
            {domains.map((d) => (
              <div key={d.label}>
                <div className="mb-1 flex justify-between gap-2 text-micro">
                  <span className="min-w-0 break-all text-fg">{d.label}</span>
                  <span className="shrink-0 font-mono text-subtle">{formatKb(d.kb)}</span>
                </div>
                <div className="h-1.5 overflow-hidden rounded-full bg-white/5">
                  <div className="h-full rounded-full bg-cyan" style={{ width: `${Math.max(6, (d.kb / maxKb) * 100)}%` }} />
                </div>
              </div>
            ))}
          </div>
        </Panel>
      ) : null}
      <Panel>
        <PanelHeader icon={<Network className="size-4" />} title="Connections" subtitle="This page only" />
        <div className="mb-3 flex flex-wrap gap-1.5">
          {(["all", "open", "inbound", "outbound", "dropped"] as const).map((f) => (
            <button
              key={f}
              type="button"
              onClick={() => setFilter(f)}
              className={cn(
                "rounded-full border px-3 py-1 text-xs capitalize",
                filter === f ? "border-line-strong bg-white/10 text-fg" : "border-transparent text-muted hover:bg-white/5",
              )}
            >
              {f === "all" ? "Traffic" : f === "open" ? "Needs review" : f}
            </button>
          ))}
        </div>
        <GroupedActivityList
          items={items}
          empty={killSwitch ? "Protection is on for this page." : "No sockets from this page yet."}
        />
      </Panel>
    </div>
  );
}

function DirBar({ label, value, total, tone }: { label: string; value: number; total: number; tone: string }) {
  return (
    <div className="flex items-center gap-2">
      <span className="w-8 text-2xs text-subtle">{label}</span>
      <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-white/5">
        <div className={cn("h-full rounded-full", tone)} style={{ width: `${Math.round((value / total) * 100)}%` }} />
      </div>
    </div>
  );
}
