import type { ButtonHTMLAttributes } from "react";
import {
  Ban,
  Check,
  Pause,
  Play,
  ShieldBan,
  Square,
  Trash2,
} from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { PORTS } from "@/lib/security/catalog";
import type { ActivityStatus, SecurityActivity } from "@/lib/security/types";
import { useSecurity } from "@/lib/security/store";
import { cn, formatKb, timeAgo } from "@/lib/utils";

const STATUS_TONE: Record<ActivityStatus, "emerald" | "rose" | "red" | "amber" | "muted"> = {
  allowed: "emerald",
  suspicious: "rose",
  unknown: "amber",
  blocked: "red",
  killed: "red",
  resolved: "muted",
  paused: "amber",
};

export function ActivityRow({
  item,
  compact,
}: {
  item: SecurityActivity;
  compact?: boolean;
}) {
  const allow = useSecurity((s) => s.allow);
  const block = useSecurity((s) => s.block);
  const kill = useSecurity((s) => s.kill);
  const pause = useSecurity((s) => s.pause);
  const resume = useSecurity((s) => s.resume);
  const setPending = useSecurity((s) => s.setPending);
  const needsDecision = item.status === "suspicious" || item.status === "unknown";
  const dead = item.status === "blocked" || item.status === "killed";
  const port = item.destinationPort ?? item.sourcePort;
  const portMeta = port != null ? PORTS[port] : undefined;

  const endItem = (note: string, okMsg: string) => {
    const ok = kill(item.id, note);
    if (ok) toast.success(okMsg);
    else toast.error(`Tamper protection blocked ending ${item.name}`);
  };

  return (
    <div
      className={cn(
        "flex items-start gap-3 rounded-md border border-line bg-elevated/60 px-3 py-2.5",
        needsDecision && "border-rose/20 bg-rose-dim/30",
        (dead || item.status === "paused") && "opacity-80",
      )}
    >
      <span
        className={cn(
          "mt-1 size-2 shrink-0 rounded-full",
          item.status === "allowed" && "bg-emerald",
          item.status === "suspicious" && "bg-rose",
          item.status === "unknown" && "bg-amber",
          item.status === "paused" && "bg-amber",
          (item.status === "blocked" || item.status === "killed") && "bg-red",
          item.status === "resolved" && "bg-subtle",
        )}
      />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="truncate text-sm font-medium text-fg">{item.name}</span>
          <Badge tone={STATUS_TONE[item.status]}>
            {item.resolveNote === "Network kill switch"
              ? "dropped"
              : item.status === "killed"
                ? "ended"
                : item.status}
          </Badge>
          {item.type === "traffic" && item.direction ? (
            <span className="text-2xs uppercase tracking-wide text-subtle">{item.direction}</span>
          ) : null}
        </div>
        {!compact ? (
          <p className="mt-0.5 line-clamp-2 text-micro text-muted">
            {item.resolveNote && dead ? item.resolveNote : item.details}
            {portMeta ? ` · ${portMeta.name}` : null}
            {item.destination ? ` · ${item.destination}${port ? `:${port}` : ""}` : null}
            {item.source ? ` · from ${item.source}` : null}
          </p>
        ) : null}
        <div className="mt-1 flex flex-wrap items-center gap-2 font-mono text-2xs text-subtle">
          {item.type === "traffic" && item.dataKb != null ? <span>{formatKb(item.dataKb)}</span> : null}
          {(item.type === "process" || item.type === "app") && item.cpu != null ? (
            <span>{item.cpu}% CPU</span>
          ) : null}
          {item.memoryMb != null ? <span>{item.memoryMb} MB</span> : null}
          <span>{timeAgo(item.createdAt)}</span>
        </div>
        {(item.type === "process" || item.type === "app") && item.cpu != null && !compact ? (
          <div className="mt-1.5 h-1 w-full overflow-hidden rounded-full bg-white/5">
            <div
              className={cn(
                "h-full rounded-full transition-all",
                item.status === "paused"
                  ? "bg-amber"
                  : item.cpu > 50
                    ? "bg-rose"
                    : item.cpu > 20
                      ? "bg-amber"
                      : "bg-emerald",
              )}
              style={{ width: `${Math.min(item.cpu, 100)}%` }}
            />
          </div>
        ) : null}
      </div>
      {!compact ? (
        <div className="flex shrink-0 gap-0.5">
          {needsDecision ? (
            <>
              <IconBtn title="Allow" onClick={() => allow(item.id)} className="text-emerald hover:bg-emerald-dim">
                <Check className="size-3.5" />
              </IconBtn>
              <IconBtn title="Block" onClick={() => block(item.id)} className="text-red hover:bg-red-dim">
                <Ban className="size-3.5" />
              </IconBtn>
              <IconBtn title="More Info" onClick={() => setPending(item)} className="text-cyan hover:bg-cyan-dim">
                <ShieldBan className="size-3.5" />
              </IconBtn>
            </>
          ) : null}
          {item.type === "process" && item.status === "paused" ? (
            <IconBtn
              title="Resume process"
              onClick={() => {
                resume(item.id);
                toast.success(`Resumed ${item.name}`);
              }}
              className="text-emerald hover:bg-emerald-dim"
            >
              <Play className="size-3.5" />
            </IconBtn>
          ) : null}
          {item.type === "process" && !dead && item.status !== "paused" ? (
            <IconBtn
              title="Pause process"
              onClick={() => {
                const ok = pause(item.id);
                if (ok) toast.success(`Paused ${item.name}`);
                else toast.error(`Tamper protection blocked pausing ${item.name}`);
              }}
              className="text-amber hover:bg-amber-dim"
            >
              <Pause className="size-3.5" />
            </IconBtn>
          ) : null}
          {(item.type === "process" || item.type === "app") && !dead ? (
            <IconBtn
              title={item.type === "app" ? "Stop app" : "Stop process"}
              onClick={() =>
                endItem(
                  item.type === "app" ? "App stopped by user" : "Process stopped by user",
                  `Stopped ${item.name}`,
                )
              }
              className="text-red hover:bg-red-dim"
            >
              <Square className="size-3.5" />
            </IconBtn>
          ) : null}
          {item.type === "traffic" && !dead ? (
            <IconBtn
              title="End connection"
              onClick={() => endItem("Connection reset by user", `Ended ${item.name}`)}
              className="text-red hover:bg-red-dim"
            >
              <Trash2 className="size-3.5" />
            </IconBtn>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

function IconBtn({
  children,
  className,
  title,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button
      type="button"
      title={title}
      aria-label={title}
      className={cn("rounded-md p-2.5 transition-colors hover:bg-white/10", className)}
      {...props}
    >
      {children}
    </button>
  );
}