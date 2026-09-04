import { create } from "zustand";
import { persist, createJSONStorage } from "zustand/middleware";
import { useMemo } from "react";
import { notify, setAppBadge, setDeviceKill, readNativeKill, hasAndroidBridge, setNativeAutoRestart, setNativeAutoLock, verifyNativePin, setNativePin, setNativePinIfUnset } from "@/lib/native";
import { clamp, uid } from "@/lib/utils";
import { DEFAULT_PERMISSIONS, HONEYPOT_DEFS, isProtectedName } from "./catalog";
import {
  applyGuard,
  blockHost,
  blockedHostList,
  bootEngine,
  drainEvents,
  linkKbpsNow,
  probeDecoy,
  probeOutbound,
  queryPermissionFlags,
  readConnection,
  sampleProcesses,
  sampleResources,
  snapshotPosture,
  unregisterGuardWorker,
} from "./engine";
import type {
  AllowItem,
  ConnectionInfo,
  DeepScanResult,
  HistoryItem,
  Honeypot,
  Indicator,
  Intrusion,
  Permission,
  ScanEvent,
  ScoreBreakdown,
  SecurityActivity,
  Settings,
  TabId,
  TamperEvent,
} from "./types";

const UNLOCK_KEY = "kysmindset_unlocked";

function computeScore(
  activities: SecurityActivity[],
  honeypots: Honeypot[],
  settings: Settings,
  connection: ConnectionInfo,
  killSwitch: boolean,
  lockdown: boolean,
): ScoreBreakdown {
  let score = 100;
  const factors: { label: string; deduction: number }[] = [];
  const threats = (activities ?? []).filter(
    (a) => a.status === "suspicious" || a.status === "unknown",
  );
  if (threats.length > 0) {
    const d = Math.min(threats.length * 5, 30);
    score -= d;
    factors.push({
      label: `${threats.length} active threat${threats.length > 1 ? "s" : ""}`,
      deduction: d,
    });
  }
  if (!settings?.alwaysOn) {
    score -= 15;
    factors.push({ label: "Always On disabled", deduction: 15 });
  }
  if (!settings?.autoRestart) {
    score -= 10;
    factors.push({ label: "Auto Restart disabled", deduction: 10 });
  }
  if (!settings?.tamperProtection) {
    score -= 15;
    factors.push({ label: "Tamper Protection disabled", deduction: 15 });
  }
  if (!connection.secure) {
    score -= 20;
    factors.push({ label: "Insecure (HTTP) connection", deduction: 20 });
  }
  if (killSwitch) score = Math.min(score + 4, 100);
  if (lockdown) score = Math.min(score + 6, 100);
  score = clamp(Math.round(score), 0, 100);
  const grade = score >= 90 ? "A" : score >= 80 ? "B" : score >= 70 ? "C" : score >= 60 ? "D" : "F";
  const status =
    score >= 90 ? "excellent" : score >= 70 ? "fair" : score >= 50 ? "at_risk" : "critical";
  const label =
    status === "excellent"
      ? "Secure"
      : status === "fair"
        ? "Fair"
        : status === "at_risk"
          ? "At Risk"
          : "Critical";
  return { score, grade, status, label, factors };
}

function seedHoneypots(): Honeypot[] {
  return HONEYPOT_DEFS.map((d) => ({
    id: d.path,
    name: d.name,
    decoyType: d.decoyType,
    description: d.description,
    severity: d.severity,
    armed: true,
    path: d.path,
  }));
}

function syncGuardFrom(get: () => SecurityState) {
  const s = get();
  const blocked = [
    ...blockedHostList(),
    ...s.activities
      .filter(
        (a) =>
          a.type === "traffic" &&
          (a.status === "blocked" || a.status === "killed") &&
          a.destination,
      )
      .map((a) => a.destination as string),
  ];
  applyGuard({
    kill: s.killSwitch,
    lockdown: s.lockdown,
    blocked,
    armed: s.honeypots.filter((h) => h.armed).map((h) => h.path),
  });
}

export interface SecurityState {
  hydrated: boolean;
  unlocked: boolean;
  tab: TabId;
  activities: SecurityActivity[];
  honeypots: Honeypot[];
  intrusions: Intrusion[];
  indicators: Indicator[];
  allowlist: AllowItem[];
  settings: Settings;
  permissions: Permission[];
  history: HistoryItem[];
  scanLog: ScanEvent[];
  killSwitch: boolean;
  droppedPackets: number;
  linkKbps: number;
  lockdown: boolean;
  tamperLog: TamperEvent[];
  lastTamper: TamperEvent | null;
  scanning: boolean;
  lastScan: number | null;
  deepScan: DeepScanResult | null;
  deepScanning: boolean;
  pendingDecision: SecurityActivity | null;
  connection: ConnectionInfo;
  tick: number;
  setHydrated: () => void;
  setTab: (t: TabId) => void;
  unlock: (pin: string) => boolean;
  lock: () => void;
  setPin: (pin: string) => void;
  toggleKillSwitch: () => void;
  toggleLockdown: () => void;
  updateActivity: (id: string, patch: Partial<SecurityActivity>) => void;
  allow: (id: string) => void;
  block: (id: string, note?: string) => void;
  blockSubnet: (id: string) => void;
  kill: (id: string, note?: string) => boolean;
  pause: (id: string) => boolean;
  resume: (id: string) => void;
  resolveAllThreats: () => void;
  refresh: () => void;
  addAllow: (item: Omit<AllowItem, "id">) => void;
  removeAllow: (id: string) => void;
  toggleHoneypot: (id: string) => void;
  runPenTest: () => void;
  runAiScan: () => Promise<void>;
  runDeepScan: () => void;
  patchSettings: (patch: Partial<Settings>) => void;
  setPermission: (id: string, granted: boolean) => void;
  setPending: (a: SecurityActivity | null) => void;
  decide: (
    id: string,
    action: "allow" | "allowlist" | "block" | "subnet" | "monitor" | "lockdown",
  ) => void;
  liveTick: () => void;
}

function pushHistory(
  history: HistoryItem[],
  action: string,
  target: string,
  detail: string,
): HistoryItem[] {
  return [{ id: uid("hx"), at: Date.now(), action, target, detail }, ...history].slice(0, 80);
}

export const useSecurity = create<SecurityState>()(
  persist(
    (set, get) => ({
      hydrated: false,
      unlocked: false,
      tab: "overview",
      activities: [],
      honeypots: seedHoneypots(),
      intrusions: [],
      indicators: [],
      allowlist: [
        { id: "al-ui", name: "Kysmindset 2", type: "process" },
        { id: "al-sw", name: "Service Worker", type: "process" },
      ],
      settings: {
        alwaysOn: true,
        autoRestart: true,
        tamperProtection: true,
        autoLockdown: true,
        slackAlerts: false,
        autoScanMin: 30,
        pin: "1234",
        autoLock: true,
        deviceLock: false,
      },
      permissions: DEFAULT_PERMISSIONS,
      history: [],
      scanLog: [],
      killSwitch: false,
      droppedPackets: 0,
      linkKbps: 0,
      lockdown: false,
      tamperLog: [],
      lastTamper: null,
      scanning: false,
      lastScan: null,
      deepScan: null,
      deepScanning: false,
      pendingDecision: null,
      connection: { effectiveType: "4G", secure: true, saveData: false },
      tick: 0,
      setHydrated: () => {
        if (get().hydrated) return;
        const finish = () => {
          const unlocked =
            typeof sessionStorage !== "undefined" &&
            sessionStorage.getItem(UNLOCK_KEY) === "1";
          const android = hasAndroidBridge();
          set({
            hydrated: true,
            unlocked,
            connection: readConnection(),
            tamperLog: get().tamperLog ?? [],
            lastTamper: get().lastTamper ?? null,
            ...(android ? { killSwitch: readNativeKill() } : {}),
          });
          if (android) {
            setNativeAutoRestart(get().settings.autoRestart !== false);
            setNativeAutoLock(get().settings.autoLock !== false);
            const p = get().settings.pin;
            if (/^\d{4}$/.test(p)) setNativePinIfUnset(p);
          }
          void bootEngine().then(async () => {
            syncGuardFrom(get);
            const flags = await queryPermissionFlags();
            set({
              permissions: get().permissions.map((p) =>
                flags[p.id] != null ? { ...p, granted: flags[p.id] } : p,
              ),
            });
            get().liveTick();
          });
        };
        const api = useSecurity.persist;
        const pending = api.hasHydrated()
          ? Promise.resolve()
          : Promise.resolve(api.rehydrate()).catch((err) => {
              console.warn("[kysmindset] persist rehydrate failed", err);
            });
        void Promise.race([pending, wait(1200)]).then(finish);
      },
      setTab: (tab) => set({ tab }),
      unlock: (pin) => {
        const ok =
          pin === "__bio__" ||
          (hasAndroidBridge() ? verifyNativePin(pin) : pin === get().settings.pin);
        if (!ok) return false;
        sessionStorage.setItem(UNLOCK_KEY, "1");
        set({ unlocked: true });
        return true;
      },
      lock: () => {
        sessionStorage.removeItem(UNLOCK_KEY);
        set({ unlocked: false, pendingDecision: null });
      },
      setPin: (pin) => {
        if (!/^\d{4}$/.test(pin)) return;
        if (hasAndroidBridge()) setNativePin(pin);
        set({
          settings: {
            ...get().settings,
            pin: hasAndroidBridge() ? "1234" : pin,
          },
        });
      },
      toggleKillSwitch: () => {
        const next = !get().killSwitch;
        const before = get().activities;
        const activities = next
          ? before.map((a) =>
              a.type === "traffic" && a.status !== "blocked" && a.status !== "killed"
                ? {
                    ...a,
                    prevStatus: a.status,
                    status: "blocked" as const,
                    resolveNote: "Network kill switch",
                  }
                : a,
            )
          : before.map((a) =>
              a.resolveNote === "Network kill switch" && a.prevStatus
                ? {
                    ...a,
                    status: a.prevStatus,
                    resolveNote: undefined,
                    prevStatus: undefined,
                  }
                : a,
            );
        set({
          killSwitch: next,
          activities,
          tab: next ? "network" : get().tab,
          linkKbps: next ? 0 : linkKbpsNow(false),
          history: pushHistory(
            get().history,
            next ? "Kill switch armed" : "Kill switch released",
            "Network",
            next
              ? "Arming device air gap (VPN) if the APK can host it."
              : "Releasing air gap.",
          ),
        });
        syncGuardFrom(get);
        if (next) void probeOutbound().then(() => get().liveTick());
        void setDeviceKill(next).then((status) => {
          if (status === "denied") {
            set({
              killSwitch: false,
              history: pushHistory(
                get().history,
                "VPN permission denied",
                "Network",
                "Air gap needs the Android VPN consent screen. Without it, only this app is blocked.",
              ),
            });
            syncGuardFrom(get);
          } else if (status === "on") {
            set({
              history: pushHistory(
                get().history,
                "Device VPN up",
                "Network",
                "Other apps should lose internet until Kill is released. Allow the VPN if Android asks.",
              ),
            });
          } else if (status === "app") {
            set({
              history: pushHistory(
                get().history,
                "App-only kill",
                "Network",
                "This build cannot cut Facebook. Use the APK and approve VPN for device-wide air gap.",
              ),
            });
          }
        });
      },
      toggleLockdown: () => {
        const next = !get().lockdown;
        let activities = get().activities;
        if (next) {
          activities = activities.map((a) =>
            a.status === "suspicious" || a.status === "unknown"
              ? {
                  ...a,
                  prevStatus: a.status,
                  status: "blocked" as const,
                  resolveNote: "Emergency lockdown",
                }
              : a,
          );
        } else {
          activities = activities.map((a) =>
            a.resolveNote === "Emergency lockdown" && a.prevStatus
              ? { ...a, status: a.prevStatus, resolveNote: undefined, prevStatus: undefined }
              : a,
          );
        }
        set({
          lockdown: next,
          activities,
          history: pushHistory(
            get().history,
            next ? "Lockdown armed" : "Lockdown lifted",
            "Device",
            next
              ? "Unknown and suspicious activity is blocked until you lift lockdown."
              : "Unknown items restored. Device back to normal.",
          ),
        });
        syncGuardFrom(get);
      },
      updateActivity: (id, patch) =>
        set({
          activities: get().activities.map((a) => (a.id === id ? { ...a, ...patch } : a)),
        }),
      allow: (id) => {
        const a = get().activities.find((x) => x.id === id);
        if (!a) return;
        set({
          activities: get().activities.map((x) =>
            x.id === id ? { ...x, status: "allowed" as const, resolveNote: "Allowed by user" } : x,
          ),
          history: pushHistory(get().history, "Allowed", a.name, a.details),
        });
      },
      block: (id, note = "Blocked by user") => {
        const a = get().activities.find((x) => x.id === id);
        if (!a) return;
        const indicators = [...get().indicators];
        const ip = a.source || a.destination;
        if (ip && !indicators.some((i) => i.value === ip)) {
          indicators.unshift({
            id: uid("ind"),
            value: ip,
            kind: "ip",
            reasoning: note,
            at: Date.now(),
          });
        }
        set({
          activities: get().activities.map((x) =>
            x.id === id ? { ...x, status: "blocked" as const, resolveNote: note } : x,
          ),
          indicators: indicators.slice(0, 40),
          history: pushHistory(get().history, "Blocked", a.name, note),
        });
        if (a.destination) blockHost(a.destination);
        syncGuardFrom(get);
      },
      blockSubnet: (id) => {
        const a = get().activities.find((x) => x.id === id);
        if (!a) return;
        get().block(id, "Subnet blocked by user from dashboard");
      },
      kill: (id, note = "Ended by user") => {
        const a = get().activities.find((x) => x.id === id);
        if (!a) return false;
        if (
          get().settings.tamperProtection &&
          a.type !== "traffic" &&
          isProtectedName(a.name) &&
          a.status === "allowed"
        ) {
          const ev: TamperEvent = {
            id: uid("tp"),
            at: Date.now(),
            target: a.name,
            action: "kill",
            actor: "user",
            cause: `Stop ${a.type} “${a.name}” — protected by Tamper Protection`,
          };
          set({
            tamperLog: [ev, ...get().tamperLog].slice(0, 40),
            lastTamper: ev,
            history: pushHistory(get().history, "Tamper blocked", a.name, ev.cause),
          });
          return false;
        }
        set({
          activities: get().activities.map((x) =>
            x.id === id
              ? { ...x, status: "killed" as const, cpu: 0, dataKb: x.dataKb, resolveNote: note }
              : x,
          ),
          history: pushHistory(get().history, "Ended", a.name, note),
        });
        if (a.type === "traffic" && a.destination) blockHost(a.destination);
        if (a.name === "Service Worker") {
          void unregisterGuardWorker();
        }
        syncGuardFrom(get);
        return true;
      },
      pause: (id) => {
        const a = get().activities.find((x) => x.id === id);
        if (!a) return false;
        if (get().settings.tamperProtection && isProtectedName(a.name) && a.status === "allowed") {
          const ev: TamperEvent = {
            id: uid("tp"),
            at: Date.now(),
            target: a.name,
            action: "pause",
            actor: "user",
            cause: `Pause ${a.type} “${a.name}” — protected by Tamper Protection`,
          };
          set({
            tamperLog: [ev, ...get().tamperLog].slice(0, 40),
            lastTamper: ev,
            history: pushHistory(get().history, "Tamper blocked", a.name, ev.cause),
          });
          return false;
        }
        set({
          activities: get().activities.map((x) =>
            x.id === id
              ? {
                  ...x,
                  prevStatus: x.status,
                  prevCpu: x.cpu,
                  status: "paused" as const,
                  cpu: 0,
                  resolveNote: "Paused by user",
                }
              : x,
          ),
          history: pushHistory(get().history, "Paused", a.name, "Process paused"),
        });
        return true;
      },
      resume: (id) => {
        const a = get().activities.find((x) => x.id === id);
        if (!a) return;
        set({
          activities: get().activities.map((x) =>
            x.id === id
              ? {
                  ...x,
                  status: x.prevStatus && x.prevStatus !== "paused" ? x.prevStatus : "allowed",
                  cpu: x.prevCpu ?? 1,
                  prevStatus: undefined,
                  prevCpu: undefined,
                  resolveNote: undefined,
                }
              : x,
          ),
          history: pushHistory(get().history, "Resumed", a.name, "Process resumed"),
        });
      },
      resolveAllThreats: () => {
        const n = get().activities.filter(
          (a) => a.status === "suspicious" || a.status === "unknown",
        ).length;
        set({
          activities: get().activities.map((a) =>
            a.status === "suspicious" || a.status === "unknown"
              ? { ...a, status: "blocked" as const, resolveNote: "Blocked automatically" }
              : a,
          ),
          history: pushHistory(
            get().history,
            "Block all threats",
            `${n} items`,
            "All suspicious items blocked.",
          ),
        });
      },
      refresh: () => {
        set({ tick: get().tick + 1, connection: readConnection() });
        get().liveTick();
      },
      addAllow: (item) =>
        set({ allowlist: [{ id: uid("al"), ...item }, ...get().allowlist] }),
      removeAllow: (id) =>
        set({ allowlist: get().allowlist.filter((a) => a.id !== id) }),
      toggleHoneypot: (id) => {
        set({
          honeypots: get().honeypots.map((h) =>
            h.id === id ? { ...h, armed: !h.armed } : h,
          ),
        });
        syncGuardFrom(get);
      },
      runPenTest: () => {
        const armed = get().honeypots.filter((h) => h.armed);
        if (armed.length === 0) return;
        set({
          history: pushHistory(
            get().history,
            "Pen test",
            `${armed.length} decoys`,
            "Probing armed decoy paths on this origin.",
          ),
        });
        void Promise.all(armed.map((hp) => probeDecoy(hp.path))).then(() => get().liveTick());
      },
      runAiScan: async () => {
        if (get().scanning) return;
        set({ scanning: true, scanLog: [] });
        const push = (message: string, kind: ScanEvent["kind"]) => {
          set({
            scanLog: [{ id: uid("sc"), at: Date.now(), message, kind }, ...get().scanLog].slice(
              0,
              80,
            ),
          });
        };
        push("Starting scan", "info");
        await wait(280);
        get().liveTick();
        const snap = await snapshotPosture();
        push(
          snap.secure ? "Transport is HTTPS" : "Insecure HTTP — session can be read",
          snap.secure ? "ok" : "threat",
        );
        await wait(220);
        push(snap.online ? "Device is online" : "Device is offline", snap.online ? "ok" : "learn");
        await wait(220);
        const flags = await queryPermissionFlags();
        set({
          permissions: get().permissions.map((p) =>
            flags[p.id] != null ? { ...p, granted: flags[p.id] } : p,
          ),
        });
        const granted = get().permissions.filter((p) => p.granted);
        push(
          granted.length
            ? `Granted: ${granted.map((p) => p.name).join(", ")}`
            : "No extra device permissions granted",
          "info",
        );
        await wait(220);
        push(
          snap.sw
            ? "Service worker is controlling this origin"
            : "Service worker not controlling — install the PWA for full intercept",
          snap.sw ? "ok" : "learn",
        );
        await wait(220);
        push(
          snap.persisted
            ? `Persistent storage on · ${snap.usedMb} / ${snap.quotaMb} MB`
            : `Storage not persisted · ${snap.usedMb} / ${snap.quotaMb} MB`,
          snap.persisted ? "ok" : "learn",
        );
        await wait(220);
        push(
          get().killSwitch
            ? "Protection is on — checking whether the phone VPN is up"
            : "Protection is off (app-only unless the VPN is approved)",
          get().killSwitch ? "learn" : "info",
        );
        await wait(220);
        if (snap.thirdPartyHosts.length) {
          push(
            `${snap.thirdPartyHosts.length} third-party host${snap.thirdPartyHosts.length === 1 ? "" : "s"}: ${snap.thirdPartyHosts.slice(0, 6).join(", ")}`,
            "learn",
          );
          if (get().settings.autoLockdown) {
            for (const host of snap.thirdPartyHosts) {
              if (get().activities.some((a) => a.name === host && a.status === "suspicious")) {
                const row = get().activities.find((a) => a.name === host);
                if (row) get().block(row.id, "Blocked by scan (tracker)");
              }
            }
          }
        } else {
          push("No third-party or tracker hosts in this session", "ok");
        }
        await wait(220);
        if (snap.thirdPartyScripts > 0) {
          push(`${snap.thirdPartyScripts} third-party script(s) in the document`, "learn");
        }
        const threats = get().activities.filter(
          (a) => a.status === "suspicious" || a.status === "unknown",
        );
        push(
          threats.length
            ? `${threats.length} live finding${threats.length === 1 ? "" : "s"} on the board`
            : "No active findings — board is clear",
          threats.length ? "threat" : "ok",
        );
        await wait(180);
        push("Scan complete", "ok");
        set({ scanning: false, lastScan: Date.now() });
        get().runDeepScan();
      },
      runDeepScan: () => {
        set({ deepScanning: true, deepScan: null });
        void snapshotPosture().then((snap) => {
          const s = scoreOf(get());
          const threats = get().activities.filter(
            (a) => a.status === "suspicious" || a.status === "unknown",
          );
          const blocked = get().activities.filter((a) => a.status === "blocked").length;
          const vulns: DeepScanResult["vulnerabilities"] = [];
          const recs: DeepScanResult["recommendations"] = [];
          const strengths: string[] = [];
          if (!snap.secure)
            vulns.push({ name: "Insecure (HTTP) connection", severity: "critical" });
          if (!snap.sw)
            vulns.push({
              name: "Service worker is not controlling this origin",
              severity: "medium",
            });
          if (snap.thirdPartyHosts.length)
            vulns.push({
              name: `${snap.thirdPartyHosts.length} third-party host${snap.thirdPartyHosts.length === 1 ? "" : "s"} in this session`,
              severity: snap.thirdPartyHosts.length > 3 ? "high" : "medium",
            });
          if (!get().settings.tamperProtection)
            vulns.push({ name: "Tamper Protection is off", severity: "high" });
          if (threats.length)
            recs.push({ action: "Block or allow remaining unknown hosts", priority: "high" });
          if (!snap.sw)
            recs.push({ action: "Install the PWA so the worker can intercept requests", priority: "high" });
          if (!snap.persisted)
            recs.push({ action: "Grant persistent storage in Config", priority: "medium" });
          if (get().settings.alwaysOn) strengths.push("Always-on monitoring is active");
          if (snap.secure) strengths.push("HTTPS transport");
          if (snap.sw) strengths.push("Service worker intercept is live");
          if (blocked > 0) strengths.push(`${blocked} hosts already blocked`);
          if (s.score >= 80) strengths.push("Posture grade is in the secure band");
          set({
            deepScanning: false,
            lastScan: Date.now(),
            deepScan: {
              assessment:
                s.score >= 85
                  ? "This origin is tight. Residual risk is third-party hosts and permissions."
                  : s.score >= 70
                    ? "Fair posture. Block unknown hosts."
                    : "Elevated risk. Resolve unknown traffic and restore protection flags.",
              vulnerabilities: vulns,
              recommendations: recs,
              strengths,
            },
          });
        });
      },
      patchSettings: (patch) => {
        set({ settings: { ...get().settings, ...patch } });
        if (typeof patch.autoRestart === "boolean") setNativeAutoRestart(patch.autoRestart);
        if (typeof patch.autoLock === "boolean") setNativeAutoLock(patch.autoLock);
      },
      setPermission: (id, granted) =>
        set({
          permissions: get().permissions.map((p) => (p.id === id ? { ...p, granted } : p)),
        }),
      setPending: (pendingDecision) => set({ pendingDecision }),
      decide: (id, action) => {
        const a = get().activities.find((x) => x.id === id);
        if (!a) {
          set({ pendingDecision: null });
          return;
        }
        if (action === "allow") get().allow(id);
        if (action === "allowlist") {
          get().allow(id);
          get().addAllow({ name: a.name, type: a.type });
        }
        if (action === "block") get().block(id);
        if (action === "subnet") get().blockSubnet(id);
        if (action === "monitor") {
          set({
            history: pushHistory(get().history, "Monitor only", a.name, "Keep watching, take no action"),
          });
        }
        if (action === "lockdown") {
          get().block(id, "Trigger lockdown");
          if (!get().lockdown) get().toggleLockdown();
        }
        set({ pendingDecision: null });
      },
      liveTick: () => {
        const st = get();
        const pausedMonitor = st.activities.some(
          (a) => a.id === "proc-kysmindset" && a.status === "paused",
        );
        if (pausedMonitor) {
          set({ tick: st.tick + 1, connection: readConnection() });
          return;
        }
        const paused = new Set(
          st.activities.filter((a) => a.status === "paused" || a.status === "killed").map((a) => a.id),
        );
        const resources = sampleResources();
        const processes = sampleProcesses().map((p) => {
          const prev = st.activities.find((a) => a.id === p.id);
          if (prev && (prev.status === "paused" || prev.status === "killed")) return prev;
          return p;
        });
        const byId = new Map<string, SecurityActivity>();
        for (const a of st.activities) {
          if (a.status === "killed" || a.status === "paused" || a.status === "blocked") byId.set(a.id, a);
        }
        for (const a of [...resources, ...processes]) {
          if (paused.has(a.id)) continue;
          const keep = byId.get(a.id);
          if (keep && (keep.status === "blocked" || keep.status === "killed")) {
            byId.set(a.id, {
              ...a,
              status: keep.status,
              resolveNote: keep.resolveNote,
              prevStatus: keep.prevStatus,
            });
          } else {
            byId.set(a.id, a);
          }
        }

        let droppedPackets = st.droppedPackets;
        let intrusions = st.intrusions;
        let indicators = st.indicators;
        let history = st.history;
        let tamperLog = st.tamperLog ?? [];
        let lastTamper = st.lastTamper ?? null;

        for (const ev of drainEvents()) {
          if (ev.type === "drop") {
            droppedPackets += 1;
            const id = `net-${ev.host}`;
            const prev = byId.get(id);
            byId.set(id, {
              id,
              type: "traffic",
              name: ev.host,
              status: "blocked",
              createdAt: ev.at,
              details: `Dropped (${ev.reason})`,
              destination: ev.host,
              protocol: "HTTPS",
              direction: "outbound",
              dataKb: prev?.dataKb ?? 0,
              resolveNote: ev.reason === "kill" ? "Network kill switch" : "Blocklist",
            });
          }
          if (ev.type === "honeypot") {
            const hp = st.honeypots.find((h) => h.path === ev.path);
            intrusions = [
              {
                id: uid("in"),
                honeypotId: hp?.id ?? ev.path,
                honeypotName: ev.name,
                source: "this device",
                payload: `${ev.method} ${ev.path}`,
                at: ev.at,
                autoBlocked: true,
              },
              ...intrusions,
            ].slice(0, 40);
            if (!indicators.some((i) => i.value === ev.path)) {
              indicators = [
                {
                  id: uid("ind"),
                  value: ev.path,
                  kind: "dns" as const,
                  reasoning: `Honeypot: ${ev.name}`,
                  at: ev.at,
                },
                ...indicators,
              ].slice(0, 40);
            }
            history = pushHistory(history, "Honeypot catch", ev.name, `${ev.method} ${ev.path}`);
            if (st.permissions.some((p) => p.id === "push" && p.granted)) {
              notify("Honeypot catch", `${ev.name} — ${ev.path}`);
            }
          }
          if (ev.type === "tamper") {
            const te: TamperEvent = {
              id: uid("tp"),
              at: ev.at,
              target: ev.target,
              action: "stop-monitor",
              actor: "intruder",
              cause: ev.cause,
            };
            tamperLog = [te, ...tamperLog].slice(0, 40);
            lastTamper = te;
            history = pushHistory(history, "Tamper blocked", ev.target, ev.cause);
          }
        }

        const activities = [...byId.values()]
          .sort((a, b) => b.createdAt - a.createdAt)
          .slice(0, 80);
        set({
          activities,
          intrusions,
          indicators,
          history,
          tick: st.tick + 1,
          connection: readConnection(),
          droppedPackets,
          linkKbps: linkKbpsNow(st.killSwitch),
          tamperLog,
          lastTamper,
        });
        const threatN = activities.filter(
          (a) => a.status === "suspicious" || a.status === "unknown",
        ).length;
        setAppBadge(threatN);
      },
    }),
    {
      name: "kysmindset-v4",
      storage: createJSONStorage(() => localStorage),
      skipHydration: true,
      version: 4,
      migrate: () => ({}) as SecurityState,
      partialize: (s) => ({
        honeypots: s.honeypots,
        allowlist: s.allowlist,
        settings: hasAndroidBridge() ? { ...s.settings, pin: "1234" } : s.settings,
        permissions: s.permissions,
        history: s.history.slice(0, 40),
        scanLog: (s.scanLog ?? []).slice(0, 40),
        lastScan: s.lastScan,
        deepScan: s.deepScan,
        killSwitch: s.killSwitch,
        droppedPackets: s.droppedPackets,
        tamperLog: (s.tamperLog ?? []).slice(0, 40),
        lastTamper: s.lastTamper ?? null,
      }),
      merge: (persisted, current) => {
        const p =
          persisted && typeof persisted === "object"
            ? (persisted as Partial<SecurityState>)
            : {};
        return {
          ...current,
          ...p,
          hydrated: false,
          unlocked: false,
          scanning: false,
          deepScanning: false,
          pendingDecision: null,
          tamperLog: Array.isArray(p.tamperLog) ? p.tamperLog : [],
          lastTamper: p.lastTamper ?? null,
          activities: current.activities,
          honeypots: Array.isArray(p.honeypots) ? p.honeypots : current.honeypots,
          settings: { ...current.settings, ...(p.settings ?? {}) },
          connection: current.connection,
        };
      },
    },
  ),
);

function wait(ms: number) {
  return new Promise((r) => setTimeout(r, ms));
}

function scoreOf(s: SecurityState) {
  return computeScore(
    s.activities,
    s.honeypots,
    s.settings,
    s.connection,
    s.killSwitch,
    s.lockdown,
  );
}

export function useScore() {
  const activities = useSecurity((s) => s.activities);
  const honeypots = useSecurity((s) => s.honeypots);
  const settings = useSecurity((s) => s.settings);
  const connection = useSecurity((s) => s.connection);
  const killSwitch = useSecurity((s) => s.killSwitch);
  const lockdown = useSecurity((s) => s.lockdown);
  return useMemo(
    () => computeScore(activities, honeypots, settings, connection, killSwitch, lockdown),
    [activities, honeypots, settings, connection, killSwitch, lockdown],
  );
}

