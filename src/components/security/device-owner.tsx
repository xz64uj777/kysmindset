import { Shield } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import {
  androidLockNow,
  androidPinNow,
  applyAndroidOwner,
  applyAndroidPin,
  isAndroidApp,
  readOwnerStatus,
  removeAndroidAdmin,
  requestAndroidAdmin,
  type OwnerStatus,
} from "@/lib/android-lock";
import { useSecurity } from "@/lib/security/store";

export function DeviceOwnerPanel() {
  const android = isAndroidApp();
  const [st, setSt] = useState<OwnerStatus | null>(null);
  const [advanced, setAdvanced] = useState(false);

  const refresh = useCallback(() => {
    setSt(readOwnerStatus());
  }, []);

  useEffect(() => {
    refresh();
    const id = window.setInterval(refresh, 2000);
    return () => window.clearInterval(id);
  }, [refresh]);

  const role = !android
    ? "Web only"
    : st?.owner
      ? "Device owner"
      : st?.admin
        ? "Device admin"
        : "Not enrolled";

  return (
    <Panel>
      <PanelHeader
        icon={<Shield className="size-4" />}
        title="Device lock"
        subtitle="One tap in Android. No computer, no factory reset."
      />
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <Badge tone={st?.owner ? "emerald" : st?.admin ? "cyan" : "muted"}>{role}</Badge>
        {st?.pinOnLock ? <Badge tone="amber">Screen pin on</Badge> : null}
        {st?.lockTask ? <Badge tone="cyan">Lock-task ready</Badge> : null}
        {st?.keyguardOff ? <Badge tone="emerald">System keyguard off</Badge> : null}
      </div>

      {!android ? (
        <p className="text-xs text-muted">
          Device lock only runs inside the Android APK.
        </p>
      ) : st?.owner ? (
        <OwnerControls st={st} setSt={setSt} />
      ) : st?.admin ? (
        <AdminControls st={st} setSt={setSt} advanced={advanced} setAdvanced={setAdvanced} />
      ) : (
        <div className="space-y-3">
          <p className="text-xs text-muted">
            Device Admin is the path almost everyone can use. Android shows one system screen.
            Lock phone now uses your system PIN first — this app cannot replace the Android lock
            screen. After you unlock the phone, Kysmindset comes back to its own PIN.
          </p>
          <p className="text-xs text-muted">
            Home and Back always leave. Pinning is optional and uses Android’s own pin dialog.
          </p>
          <Button
            size="sm"
            onClick={() => {
              requestAndroidAdmin();
              toast.message("Allow Kysmindset as device admin on the next screen.");
            }}
          >
            Enable device admin
          </Button>
        </div>
      )}
    </Panel>
  );
}

function AdminControls({
  st,
  setSt,
  advanced,
  setAdvanced,
}: {
  st: OwnerStatus;
  setSt: (s: OwnerStatus | null) => void;
  advanced: boolean;
  setAdvanced: (v: boolean) => void;
}) {
  return (
    <div className="space-y-3">
      <p className="text-xs text-muted">
        Admin is on. Lock phone now (and screen-off if that toggle is on) shows the Android lock
        screen first. After your phone PIN or fingerprint, this app opens on its PIN. Home and
        Back leave unless you pin.
      </p>
      <ToggleRow
        title="Pin app while locked"
        desc="Android screen pinning. Home is blocked. Back does not leave. Unpin with Recents + Back (or turn this off). The system lock screen still shows first."
        checked={Boolean(st.pinOnLock)}
        onChange={(v) => {
          const next = applyAndroidPin(v);
          setSt(next);
          if (v) {
            androidPinNow();
            toast.message("Android may ask to pin this screen. Recents + Back unpins.");
          } else {
            toast.success("Screen pin off. Home and Back leave again.");
          }
        }}
      />
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="outline"
          onClick={() => {
            useSecurity.getState().lock();
            androidLockNow();
            toast.message("Android lock first. This app’s PIN is waiting behind it.");
          }}
        >
          Lock phone now
        </Button>
        <Button
          size="sm"
          variant="outline"
          onClick={() => {
            const next = removeAndroidAdmin();
            setSt(next);
            toast.message("Device admin removed.");
          }}
        >
          Remove admin
        </Button>
      </div>
      <button
        type="button"
        className="text-2xs text-subtle underline-offset-2 hover:text-muted hover:underline"
        onClick={() => setAdvanced(!advanced)}
      >
        {advanced ? "Hide advanced Owner steps" : "Advanced: Device Owner (ADB / reset)"}
      </button>
      {advanced ? <OwnerAdbHint /> : null}
    </div>
  );
}

function OwnerControls({
  st,
  setSt,
}: {
  st: OwnerStatus;
  setSt: (s: OwnerStatus | null) => void;
}) {
  return (
    <div className="space-y-3">
      <p className="text-xs text-muted">
        This phone treats Kysmindset as the device policy controller. Home, Recents, and the
        system keyguard stay available unless you opt into pinning.
      </p>
      <ToggleRow
        title="Replace system keyguard"
        desc="Turns the Android lock screen off so only the Kysmindset PIN is shown. Fails if the phone still has its own PIN or pattern."
        checked={Boolean(st.replaceKeyguard)}
        onChange={(v) => {
          const next = applyAndroidOwner(v);
          setSt(next);
          if (v && next && !next.keyguardOff) {
            toast.error(
              "Android kept its keyguard. Remove the phone PIN/pattern, then toggle this again.",
            );
          } else {
            toast.success(v ? "Kysmindset will own the lock." : "System keyguard restored.");
          }
        }}
      />
      <Button
        size="sm"
        variant="outline"
        onClick={() => {
          const next = removeAndroidAdmin();
          setSt(next);
          toast.message(
            "Asked Android to drop admin. Device Owner may need a factory reset to fully clear.",
          );
        }}
      >
        Remove admin
      </Button>
    </div>
  );
}

function OwnerAdbHint() {
  return (
    <div className="space-y-2 rounded-md border border-line bg-elevated p-3">
      <p className="text-xs text-muted">
        Device Owner can hide Home and replace the system lock. Android blocks an app from giving
        itself that role after you sign into accounts. Skip this unless you can factory-reset.
      </p>
    </div>
  );
}

function ToggleRow({
  title,
  desc,
  checked,
  onChange,
}: {
  title: string;
  desc: string;
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <div className="flex items-start gap-3 rounded-md border border-line bg-elevated p-3">
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-fg">{title}</span>
          {checked ? <Badge tone="emerald">Active</Badge> : <Badge>Off</Badge>}
        </div>
        <p className="text-micro text-muted">{desc}</p>
      </div>
      <Switch checked={checked} onCheckedChange={onChange} />
    </div>
  );
}
