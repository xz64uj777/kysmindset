import { Activity, Radar, RefreshCw, ShieldAlert, ShieldCheck, Terminal, Wifi, WifiOff } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { readNativeKill } from "@/lib/native";
import { useScore, useSecurity } from "@/lib/security/store";
import { loadVpnAllow } from "@/lib/vpn-allow";
import { cn, timeAgo } from "@/lib/utils";
import { Panel, PanelHeader, ScoreTone, StatusDot } from "./chrome";
import { GroupedActivityList } from "./grouped-list";

export function OverviewPanel() {
  const activities = useSecurity((s) => s.activities);
  const connection = useSecurity((s) => s.connection);
  const scanning = useSecurity((s) => s.scanning);
  const lastScan = useSecurity((s) => s.lastScan);
  const runAiScan = useSecurity((s) => s.runAiScan);
  const indicators = useSecurity((s) => s.indicators);
  const allowlist = useSecurity((s) => s.allowlist);
  const scanLog = useSecurity((s) => s.scanLog);
  const killSwitch = useSecurity((s) => s.killSwitch);
  const toggleKillSwitch = useSecurity((s) => s.toggleKillSwitch);
  const [deviceVpn, setDeviceVpn] = useState(() => readNativeKill());
  const [vpnAllowN, setVpnAllowN] = useState(() => loadVpnAllow().length);
  useEffect(() => {
    const sync = () => {
      const n = readNativeKill();
      setDeviceVpn(n);
      setVpnAllowN(loadVpnAllow().length);
      if (n && !useSecurity.getState().killSwitch) {
        useSecurity.setState({ killSwitch: true });
      }
    };
    sync();
    const id = window.setInterval(sync, 1500);
    return () => window.clearInterval(id);
  }, [killSwitch]);
  const score = useScore();
  const tone = ScoreTone(score.status);
  const threats = activities.filter((a) => a.status === "suspicious" || a.status === "unknown");
  const blocked = activities.filter((a) => a.status === "blocked" || a.status === "killed");
  const allowed = activities.filter((a) => a.status === "allowed");
  const dims = [
    {
      name: "Network Exposure",
      value: Math.max(12, 100 - threats.filter((t) => t.type === "traffic").length * 18),
      desc: threats.some((t) => t.type === "traffic")
        ? "Third-party or tracker hosts in this session"
        : "Only first-party traffic so far",
    },
    {
      name: "Runtime Integrity",
      value: Math.max(18, 100 - threats.filter((t) => t.type === "process").length * 22),
      desc: threats.some((t) => t.type === "process")
        ? "Service worker is not controlling this origin"
        : "UI thread and worker look healthy",
    },
    {
      name: "Transport",
      value: connection.secure ? 96 : 40,
      desc: connection.secure ? "HTTPS" : "HTTP — session can be read",
    },
  ];

  const copySnapshot = async () => {
    const text = [
      `Kysmindset ${score.grade} ${score.score} · ${score.label}`,
      `Link ${connection.secure ? "HTTPS" : "HTTP"} · ${connection.effectiveType}`,
      killSwitch ? "Protection on" : "Protection off",
      lastScan ? `Last scan ${new Date(lastScan).toISOString()}` : "No scan yet",
      score.factors.map((f) => `- ${f.label} (−${f.deduction})`).join("\n"),
    ]
      .filter(Boolean)
      .join("\n");
    try {
      await navigator.clipboard.writeText(text);
      toast.success("Posture snapshot copied");
    } catch {
      toast.message(text);
    }
  };

  return (
    <div className="space-y-4">
      <Panel>
        <PanelHeader
          icon={<ShieldCheck className="size-4" />}
          title="Protection"
          subtitle="Cuts other apps’ internet. Apps you allow in Config still get through."
        />
        <div className="grid gap-2 sm:grid-cols-3">
          <div className="rounded-md border border-line bg-elevated p-3">
            <div className="text-2xs uppercase tracking-wide text-subtle">Phone VPN</div>
            <div className={cn("mt-1 text-sm font-semibold", deviceVpn ? "text-emerald" : "text-muted")}>
              {deviceVpn ? "On" : "Off"}
            </div>
          </div>
          <div className="rounded-md border border-line bg-elevated p-3">
            <div className="text-2xs uppercase tracking-wide text-subtle">App guard</div>
            <div className={cn("mt-1 text-sm font-semibold", killSwitch ? "text-emerald" : "text-muted")}>
              {killSwitch ? "On" : "Off"}
            </div>
          </div>
          <div className="rounded-md border border-line bg-elevated p-3">
            <div className="text-2xs uppercase tracking-wide text-subtle">Do not block</div>
            <div className="mt-1 text-sm font-semibold text-fg">
              {vpnAllowN} app{vpnAllowN === 1 ? "" : "s"}
            </div>
          </div>
        </div>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Button
            size="sm"
            onClick={() => {
              const arming = !killSwitch;
              toggleKillSwitch();
              if (arming) toast.message("Allow the VPN screen if Android shows it.");
              else toast.success("Protection off.");
            }}
          >
            {killSwitch ? "Stop Protection" : "Start Protection"}
          </Button>
          <span className="text-2xs text-subtle">
            {killSwitch
              ? "Protection is on. Android may show an air-gap VPN notification."
              : "Turns on the phone VPN. Pick apps that should stay online in Config first if you want."}
          </span>
        </div>
      </Panel>
      <Panel>
        <PanelHeader icon={<Radar className="size-4" />} title="AI Security Engine" subtitle="Analyzes, learns & decides" />
        <div className="flex flex-wrap items-center gap-2">
          <Button size="sm" onClick={() => void runAiScan()} disabled={scanning}>
            {scanning ? "Scanning..." : "Run scan"}
          </Button>
          {lastScan ? <span className="text-micro text-subtle">Last scan {timeAgo(lastScan)}</span> : null}
        </div>
        <p className="mt-3 text-micro text-muted">
          {connection.secure ? "Secure" : "Insecure"} link
          {killSwitch ? " · Air gap" : ""} · {allowlist.length} trusted · {indicators.length} learned indicators
        </p>
      </Panel>
      <Panel>
        <PanelHeader icon={<Terminal className="size-4" />} title="Live feed" subtitle="Engine output while a scan runs" />
        <ScanFeed log={scanLog} scanning={scanning} />
      </Panel>
      <FoundPanel />
      <QuickActions />
      <Panel>
        <PanelHeader
          icon={<ShieldCheck className={cn("size-4", tone.color)} />}
          title="Security Score"
          subtitle="Real-time posture"
          iconClass={tone.bg}
        />
        <div className="flex items-end justify-between gap-3">
          <div className="flex items-center gap-4">
            <div className={cn("text-5xl font-bold tabular-nums", tone.color)}>{score.score}</div>
            <div>
              <div className={cn("text-lg font-bold", tone.color)}>Grade {score.grade}</div>
              <div className="text-xs text-muted">{score.label}</div>
            </div>
          </div>
          <Button size="sm" variant="ghost" onClick={() => void copySnapshot()}>
            Copy snapshot
          </Button>
        </div>
        {score.factors.length > 0 ? (
          <ul className="mt-3 space-y-1 text-micro text-muted">
            {score.factors.map((f) => (
              <li key={f.label} className="flex justify-between">
                <span>{f.label}</span>
                <span className="text-rose">-{f.deduction}</span>
              </li>
            ))}
          </ul>
        ) : null}
        <div className="mt-4 space-y-3">
          {dims.map((d) => (
            <div key={d.name}>
              <div className="mb-1 flex justify-between text-micro">
                <span className="text-fg">{d.name}</span>
                <span className="font-mono text-muted tabular-nums">{d.value}%</span>
              </div>
              <div className="h-1.5 overflow-hidden rounded-full bg-white/5">
                <div className="h-full rounded-full bg-cyan" style={{ width: `${d.value}%` }} />
              </div>
              <p className="mt-1 text-2xs text-subtle">{d.desc}</p>
            </div>
          ))}
        </div>
        <p className="mt-3 text-2xs text-subtle">
          {allowed.length} known safe · {blocked.length} cut this session
        </p>
      </Panel>
    </div>
  );
}

function FoundPanel() {
  const lastScan = useSecurity((s) => s.lastScan);
  const deepScan = useSecurity((s) => s.deepScan);
  const scanning = useSecurity((s) => s.scanning);
  const activities = useSecurity((s) => s.activities);
  const found = activities.filter(
    (a) => a.type === "traffic" && (a.status === "suspicious" || a.status === "unknown" || a.status === "blocked"),
  );
  if (!lastScan && !deepScan) return null;
  return (
    <Panel>
      <PanelHeader
        icon={<ShieldAlert className="size-4" />}
        title="Found"
        subtitle={
          scanning
            ? "Scan still running — results stay here when it finishes"
            : lastScan
              ? `Last scan ${timeAgo(lastScan)}`
              : "From the last scan"
        }
        iconClass="bg-rose-dim text-rose"
      />
      {deepScan ? (
        <div className="mb-3 space-y-2">
          <p className="text-sm text-fg">{deepScan.assessment}</p>
          {deepScan.vulnerabilities.length > 0 ? (
            <ul className="space-y-1.5">
              {deepScan.vulnerabilities.map((v) => (
                <li key={v.name} className="rounded-md border border-line bg-elevated px-3 py-2">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="text-sm font-medium text-fg">{v.name}</span>
                    <Badge tone={v.severity === "critical" || v.severity === "high" ? "rose" : v.severity === "medium" ? "amber" : "muted"}>
                      {v.severity}
                    </Badge>
                  </div>
                </li>
              ))}
            </ul>
          ) : null}
          {deepScan.recommendations.length > 0 ? (
            <ul className="space-y-1 text-micro text-muted">
              {deepScan.recommendations.map((r) => (
                <li key={r.action}>
                  <span className="uppercase text-subtle">{r.priority}</span>
                  {" — "}
                  {r.action}
                </li>
              ))}
            </ul>
          ) : null}
          {deepScan.strengths.length > 0 ? (
            <p className="text-micro text-emerald">{deepScan.strengths.join(" · ")}</p>
          ) : null}
        </div>
      ) : null}
      <GroupedActivityList
        items={found}
        empty={
          scanning
            ? "Hosts land here as the scan classifies them."
            : "No third-party or tracker hosts in this session."
        }
      />
    </Panel>
  );
}

function ScanFeed({
  log,
  scanning,
}: {
  log: { id: string; at: number; message: string; kind: "info" | "threat" | "ok" | "learn" }[];
  scanning: boolean;
}) {
  const box = useRef<HTMLDivElement>(null);
  const lines = Array.isArray(log) ? [...log].reverse() : [];
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
  }, [log, scanning]);
  return (
    <div ref={box} className="max-h-72 overflow-y-auto rounded-lg border border-line bg-bg/60 px-3 py-2 font-mono text-2xs">
      {lines.length === 0 && !scanning ? (
        <p className="py-6 text-center text-subtle">Tap Run AI Scan — live engine output appears here.</p>
      ) : (
        <div className="space-y-1.5">
          {lines.map((e) => (
            <div key={e.id} className="flex gap-2">
              <StatusDot
                tone={e.kind === "threat" ? "rose" : e.kind === "ok" ? "emerald" : e.kind === "learn" ? "cyan" : "muted"}
              />
              <span
                className={
                  e.kind === "threat" ? "text-rose" : e.kind === "ok" ? "text-emerald" : e.kind === "learn" ? "text-cyan" : "text-muted"
                }
              >
                {e.message}
              </span>
            </div>
          ))}
          {scanning ? <div className="text-cyan">▌ analyzing…</div> : null}
        </div>
      )}
    </div>
  );
}

function QuickActions() {
  const resolveAllThreats = useSecurity((s) => s.resolveAllThreats);
  const refresh = useSecurity((s) => s.refresh);
  const setTab = useSecurity((s) => s.setTab);
  const activities = useSecurity((s) => s.activities);
  const pending = activities.filter((a) => a.status === "suspicious" || a.status === "unknown");
  const actions = [
    {
      key: "block",
      label: "Block open items",
      desc: pending.length ? "Cut everything still waiting" : "Nothing waiting",
      color: "text-red border-red/20 bg-red-dim hover:bg-red/20",
      icon: ShieldAlert,
      onClick: resolveAllThreats,
      disabled: pending.length === 0,
    },
    {
      key: "refresh",
      label: "Refresh",
      desc: "Reload monitors",
      color: "text-cyan border-cyan/20 bg-cyan-dim hover:bg-cyan/20",
      icon: RefreshCw,
      onClick: refresh,
      disabled: false,
    },
  ];
  return (
    <Panel>
      <PanelHeader icon={<Activity className="size-4" />} title="Actions" subtitle="One-tap operations" iconClass="bg-elevated text-muted" />
      {pending.length > 0 ? (
        <button
          type="button"
          onClick={() => setTab("network")}
          className="mb-3 w-full rounded-md border border-rose/25 bg-rose-dim/40 px-3 py-2 text-left text-xs text-rose"
        >
          Open items are on Network — tap to review hosts
        </button>
      ) : null}
      <div className="grid grid-cols-1 gap-2.5 sm:grid-cols-2">
        {actions.map((a) => {
          const Icon = a.icon;
          return (
            <button
              key={a.key}
              type="button"
              disabled={a.disabled}
              onClick={a.onClick}
              className={cn(
                "flex items-center gap-3 rounded-md border p-3 text-left transition-colors disabled:cursor-not-allowed disabled:opacity-40",
                a.color,
              )}
            >
              <div className="flex size-8 shrink-0 items-center justify-center rounded-sm bg-white/5">
                <Icon className="size-4" />
              </div>
              <div className="min-w-0">
                <div className="truncate text-xs font-medium text-fg">{a.label}</div>
                <div className="truncate text-2xs text-subtle">{a.desc}</div>
              </div>
            </button>
          );
        })}
      </div>
    </Panel>
  );
}
