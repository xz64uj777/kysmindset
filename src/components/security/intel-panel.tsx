import { useState } from "react";
import { EyeOff, History, Shield, ShieldCheck } from "lucide-react";
import type { IntelSection } from "@/lib/security/types";
import { useScore, useSecurity } from "@/lib/security/store";
import { cn } from "@/lib/utils";
import { HistoryPanel, HoneypotPanel, PosturePanel, TimelinePanel } from "./panels";

const SECTIONS: { id: IntelSection; label: string; icon: typeof Shield }[] = [
  { id: "timeline", label: "Timeline", icon: History },
  { id: "posture", label: "Posture", icon: ShieldCheck },
  { id: "history", label: "History", icon: Shield },
  { id: "honeypot", label: "Honeypot", icon: EyeOff },
];

export function IntelPanel({ initial }: { initial?: IntelSection } = {}) {
  const [section, setSection] = useState<IntelSection>(initial ?? "timeline");
  const score = useScore();
  const historyN = useSecurity((s) => s.history.length);
  const armed = useSecurity((s) => s.honeypots.filter((h) => h.armed).length);
  const traps = useSecurity((s) => s.honeypots.length);
  const probes = useSecurity((s) => s.intrusions.length);
  const indicators = useSecurity((s) => s.indicators.length);

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        <Mini k="Score" v={`${score.score} ${score.grade}`} />
        <Mini k="History" v={String(historyN)} />
        <Mini k="Decoys" v={`${armed}/${traps}`} />
        <Mini k="Intel IOCs" v={String(indicators + probes)} />
      </div>
      <div className="flex flex-wrap gap-1.5">
        {SECTIONS.map((s) => {
          const Icon = s.icon;
          const active = section === s.id;
          return (
            <button
              key={s.id}
              type="button"
              onClick={() => setSection(s.id)}
              className={cn(
                "inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-xs font-medium",
                active ? "bg-cyan-dim text-cyan ring-1 ring-cyan/40" : "text-muted hover:bg-white/5 hover:text-fg",
              )}
            >
              <Icon className="size-3.5" />
              {s.label}
            </button>
          );
        })}
      </div>
      {section === "timeline" && <TimelinePanel />}
      {section === "posture" && <PosturePanel />}
      {section === "history" && <HistoryPanel />}
      {section === "honeypot" && <HoneypotPanel />}
    </div>
  );
}

function Mini({ k, v }: { k: string; v: string }) {
  return (
    <div className="rounded-md border border-line bg-surface px-3 py-2">
      <div className="text-sm font-semibold text-fg tabular-nums">{v}</div>
      <div className="text-2xs text-subtle">{k}</div>
    </div>
  );
}
