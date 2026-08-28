import {
  Bell,
  EyeOff,
  History,
  LayoutGrid,
  Lock,
  Network,
  Power,
  Radar,
  Settings,
  Shield,
  ShieldAlert,
  ShieldCheck,
  Wifi,
  WifiOff,
} from "lucide-react";
import type { ReactNode } from "react";
import { useEffect, useState } from "react";
import { Toaster, toast } from "sonner";
import type { TabId } from "@/lib/security/types";
import { useSecurity } from "@/lib/security/store";
import { isStandalone, requestWakeLock, setAppBadge } from "@/lib/native";
import { cn, timeAgo } from "@/lib/utils";
import { BgField, StatusDot } from "./chrome";
import { DecisionDialog, SosDialog } from "./dialogs";
import { InstallBar } from "./install-bar";
import {
  AlertsPanel,
  HistoryPanel,
  HoneypotPanel,
  NetworkPanel,
  PosturePanel,
  SystemPanel,
  TimelinePanel,
} from "./panels";
import { ActionLegend } from "./activity-row";
import { ConfigScreen } from "./config-screen";
import { OverviewPanel } from "./overview-panel";

const TABS: { id: TabId; label: string; icon: typeof LayoutGrid }[] = [
  { id: "overview", label: "Overview", icon: LayoutGrid },
  { id: "alerts", label: "Alerts", icon: Bell },
  { id: "network", label: "Network", icon: Network },
  { id: "system", label: "System", icon: Radar },
  { id: "honeypot", label: "Honeypot", icon: EyeOff },
  { id: "timeline", label: "Timeline", icon: History },
  { id: "posture", label: "Posture", icon: ShieldCheck },
  { id: "history", label: "History", icon: Shield },
  { id: "config", label: "Config", icon: Settings },
];

export function Dashboard() {
  const tab = useSecurity((s) => s.tab);
  const setTab = useSecurity((s) => s.setTab);
  const lock = useSecurity((s) => s.lock);
  const killSwitch = useSecurity((s) => s.killSwitch);
  const toggleKillSwitch = useSecurity((s) => s.toggleKillSwitch);
  const droppedPackets = useSecurity((s) => s.droppedPackets);
  const linkKbps = useSecurity((s) => s.linkKbps);
  const lockdown = useSecurity((s) => s.lockdown);
  const toggleLockdown = useSecurity((s) => s.toggleLockdown);
  const lastTamper = useSecurity((s) => s.lastTamper);
  const connection = useSecurity((s) => s.connection);
  const activities = useSecurity((s) => s.activities);
  const liveTick = useSecurity((s) => s.liveTick);
  const settings = useSecurity((s) => s.settings);
  const runAiScan = useSecurity((s) => s.runAiScan);
  const [sos, setSos] = useState(false);

  const threats = activities.filter((a) => a.status === "suspicious" || a.status === "unknown");
  const blocked = activities.filter((a) => a.status === "blocked" || a.status === "killed");
  const allowed = activities.filter((a) => a.status === "allowed");
  const netN = threats.filter((a) => a.type === "traffic").length;
  const sysN = threats.filter((a) => a.type === "process" || a.type === "app").length;

  useEffect(() => {
    const id = window.setInterval(() => liveTick(), 2000);
    return () => window.clearInterval(id);
  }, [liveTick]);

  useEffect(() => {
    if (!settings.autoScanMin) return;
    const id = window.setInterval(() => void runAiScan(), settings.autoScanMin * 60_000);
    return () => window.clearInterval(id);
  }, [settings.autoScanMin, runAiScan]);

  useEffect(() => {
    if (!settings.alwaysOn) return;
    let sent: WakeLockSentinel | null = null;
    void requestWakeLock().then((s) => {
      sent = s;
    });
    return () => {
      void sent?.release();
    };
  }, [settings.alwaysOn]);

  useEffect(() => {
    if (settings.autoLock === false) return;
    const onVis = () => {
      if (document.visibilityState === "hidden" && isStandalone()) lock();
    };
    document.addEventListener("visibilitychange", onVis);
    return () => document.removeEventListener("visibilitychange", onVis);
  }, [settings.autoLock, lock]);

  useEffect(() => {
    setAppBadge(threats.length);
  }, [threats.length]);

  const badges: Partial<Record<TabId, number>> = {
    alerts: threats.length,
    network: netN,
    system: sysN,
  };

  return (
    <div className="relative min-h-dvh bg-bg text-fg">
      <BgField />
      <Toaster theme="dark" position="top-center" />
      <DecisionDialog />
      <SosDialog open={sos} onOpenChange={setSos} />
      <div className="relative z-10 mx-auto max-w-6xl px-4 py-6 pt-safe pb-safe">
        <header className="mb-3 flex items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <div className="flex size-10 items-center justify-center rounded-md border border-cyan/20 bg-cyan-dim">
              <ShieldCheck className="size-5 text-cyan" />
            </div>
            <div>
              <h1 className="text-base font-semibold">Security Center</h1>
              <div className="flex items-center gap-2 text-2xs text-subtle">
                {killSwitch ? (
                  <span className="flex items-center gap-1 font-medium text-red">
                    <WifiOff className="size-2.5" />
                    OFFLINE · 0 KB/s
                  </span>
                ) : (
                  <>
                    {connection.effectiveType !== "UNKNOWN" ? (
                      <span className="flex items-center gap-1">
                        <Wifi className="size-2.5" />
                        {connection.effectiveType}
                      </span>
                    ) : null}
                    <span className="font-mono tabular-nums">{linkKbps} KB/s</span>
                    <span className={cn("flex items-center gap-1", connection.secure ? "text-emerald" : "text-amber")}>
                      <StatusDot tone={connection.secure ? "emerald" : "amber"} className="size-1.5" />
                      {connection.secure ? "Secure" : "Insecure"}
                    </span>
                  </>
                )}
              </div>
            </div>
          </div>
          <div className="flex items-center gap-1.5">
            <button
              type="button"
              title="Network Kill Switch"
              aria-pressed={killSwitch}
              onClick={() => {
                const arming = !killSwitch;
                toggleKillSwitch();
                if (arming) {
                  toast.error("Kill switch armed — third-party fetches from this app are blocked.");
                } else {
                  toast.success("Kill switch released — third-party fetches allowed.");
                }
              }}
              className={cn(
                "flex items-center gap-1.5 rounded-md border px-2.5 py-2 text-sm font-medium transition-colors",
                killSwitch
                  ? "kill-pulse border-red/50 bg-red-dim text-red"
                  : "border-line bg-elevated text-muted hover:bg-white/10 hover:text-fg",
              )}
            >
              {killSwitch ? <WifiOff className="size-4" /> : <Wifi className="size-4" />}
              <span>{killSwitch ? "Armed" : "Kill"}</span>
            </button>
            <button
              type="button"
              title="Emergency Lockdown"
              aria-pressed={lockdown}
              onClick={() => {
                const arming = !lockdown;
                toggleLockdown();
                if (arming) toast.error("Lockdown armed — unknown activity is blocked.");
                else toast.success("Lockdown lifted — device back to normal.");
              }}
              className={cn(
                "flex items-center gap-1.5 rounded-md border px-3 py-2 text-sm font-medium transition-colors",
                lockdown
                  ? "kill-pulse border-red/50 bg-red-dim text-red"
                  : "border-line bg-elevated text-muted hover:bg-white/10 hover:text-fg",
              )}
            >
              <Power className="size-4" />
              <span className="hidden sm:inline">{lockdown ? "Locked" : "Lockdown"}</span>
            </button>
            <button
              type="button"
              title="Lock"
              onClick={lock}
              className="flex items-center gap-2 rounded-md border border-line bg-elevated px-3 py-2 text-sm text-muted transition-colors hover:bg-white/10 hover:text-fg"
            >
              <Lock className="size-4" />
              <span className="hidden sm:inline">Lock</span>
            </button>
          </div>
        </header>

        <nav className="sticky top-0 z-30 -mx-4 mb-4 border-b border-cyan/15 bg-bg/95 px-4 py-2 backdrop-blur-xl">
          <div className="flex items-center gap-1 overflow-x-auto pb-0.5">
            {TABS.map((t) => {
              const Icon = t.icon;
              const active = tab === t.id;
              const badge = badges[t.id] ?? 0;
              return (
                <button
                  key={t.id}
                  type="button"
                  onClick={() => setTab(t.id)}
                  className={cn(
                    "flex shrink-0 items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-medium transition-colors",
                    active
                      ? "bg-cyan-dim text-cyan ring-1 ring-cyan/40"
                      : "text-muted hover:bg-white/5 hover:text-fg",
                  )}
                >
                  <Icon className="size-3.5" />
                  {t.label}
                  {badge > 0 ? (
                    <span className="rounded-full bg-rose px-1.5 py-0.5 text-2xs font-bold text-bg">
                      {badge}
                    </span>
                  ) : null}
                </button>
              );
            })}
          </div>
        </nav>

        <InstallBar />

        {killSwitch ? (
          <div className="mb-4 flex flex-wrap items-center justify-between gap-2 rounded-md border border-red/30 bg-red-dim px-3 py-3">
            <div className="flex min-w-0 items-start gap-2">
              <WifiOff className="mt-0.5 size-4 shrink-0 text-red" />
              <div>
                <p className="text-sm font-semibold text-red">Kill switch armed — air gap</p>
                <p className="text-xs text-red/80">
                  All third-party fetches from this app are failed. {droppedPackets.toLocaleString()} requests dropped · 0 KB/s
                </p>
              </div>
            </div>
            <button
              type="button"
              onClick={() => {
                toggleKillSwitch();
                toast.success("Kill switch released — third-party fetches allowed.");
              }}
              className="shrink-0 rounded-md border border-red/40 bg-elevated px-3 py-1.5 text-xs font-medium text-fg hover:bg-white/10"
            >
              Release
            </button>
          </div>
        ) : null}

        {lockdown ? (
          <div className="mb-4 flex items-center justify-between gap-2 rounded-md border border-amber/20 bg-amber-dim px-3 py-2">
            <p className="text-xs text-amber">
              DEVICE LOCKDOWN — All unknown activity is blocked. Resolve threats to return to normal.
            </p>
            <button type="button" onClick={() => setSos(true)} className="shrink-0 text-xs font-medium text-red">
              SOS Alert
            </button>
          </div>
        ) : null}

        {lastTamper ? (
          <div className="mb-4 flex items-start gap-2 rounded-md border border-amber/20 bg-amber-dim px-3 py-2">
            <ShieldAlert className="mt-0.5 size-4 shrink-0 text-amber" />
            <div className="min-w-0">
              <p className="text-xs font-medium text-amber">
                Tamper protection blocked {lastTamper.actor === "intruder" ? "an intrusion" : "your action"}
              </p>
              <p className="text-micro text-muted">
                {lastTamper.cause} · {timeAgo(lastTamper.at)}
              </p>
            </div>
          </div>
        ) : null}

        <div className="mb-2.5 space-y-1.5">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex flex-wrap items-center gap-3">
              <Legend tone="emerald" label="Allowed" />
              <Legend tone="rose" label="Threats" />
              <Legend tone="red" label="Blocked" />
              <Legend tone="teal" label="Resolved" />
            </div>
            {killSwitch ? (
              <div className="flex items-center gap-1.5 text-2xs font-medium text-red">
                <span className="live-dot size-1.5 rounded-full bg-red" />
                Air gap · 0 KB/s
              </div>
            ) : (
              <div className="flex items-center gap-1.5 text-2xs font-medium text-emerald">
                <span className="live-dot size-1.5 rounded-full bg-emerald" />
                Live · {linkKbps} KB/s
              </div>
            )}
          </div>
          {tab === "alerts" || tab === "network" || tab === "system" ? <ActionLegend /> : null}
        </div>

        <div className="mb-4 grid grid-cols-2 gap-2.5 sm:grid-cols-4">
          <StatCard icon={<ShieldCheck className="size-4" />} label="Allowed" value={allowed.length} tone="emerald" />
          <StatCard icon={<Bell className="size-4" />} label="Threats" value={threats.length} tone="rose" />
          <StatCard icon={<WifiOff className="size-4" />} label="Blocked" value={blocked.length} tone="red" />
          <StatCard icon={<Radar className="size-4" />} label="Monitored" value={activities.length} tone="cyan" />
        </div>

        {tab === "overview" && <OverviewPanel />}
        {tab === "alerts" && <AlertsPanel />}
        {tab === "network" && <NetworkPanel />}
        {tab === "system" && <SystemPanel />}
        {tab === "honeypot" && <HoneypotPanel />}
        {tab === "timeline" && <TimelinePanel />}
        {tab === "posture" && <PosturePanel />}
        {tab === "history" && <HistoryPanel />}
        {tab === "config" && <ConfigScreen />}
      </div>
    </div>
  );
}

function Legend({ tone, label }: { tone: "emerald" | "rose" | "red" | "teal"; label: string }) {
  return (
    <div className="flex items-center gap-1.5">
      <StatusDot tone={tone} />
      <span className="text-micro text-muted">{label}</span>
    </div>
  );
}

function StatCard({
  icon,
  label,
  value,
  tone,
}: {
  icon: ReactNode;
  label: string;
  value: number;
  tone: "emerald" | "rose" | "red" | "cyan";
}) {
  const colors = {
    emerald: "text-emerald",
    rose: "text-rose",
    red: "text-red",
    cyan: "text-cyan",
  };
  return (
    <div className="flex items-center gap-2.5 rounded-md border border-line bg-surface px-3 py-2.5">
      <div className={cn("flex size-8 shrink-0 items-center justify-center rounded-sm bg-elevated", colors[tone])}>
        {icon}
      </div>
      <div>
        <div className="text-lg font-bold leading-none text-fg tabular-nums">{value}</div>
        <div className="mt-0.5 text-2xs text-subtle">{label}</div>
      </div>
    </div>
  );
}
