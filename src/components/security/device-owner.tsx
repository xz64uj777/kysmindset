import { Copy, Shield } from "lucide-react";
import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import {
  applyAndroidOwner,
  isAndroidApp,
  readOwnerStatus,
  removeAndroidAdmin,
  requestAndroidAdmin,
  type OwnerStatus,
} from "@/lib/android-lock";
import { Panel, PanelHeader } from "./chrome";

const ADB =
  "adb shell dpm set-device-owner app.kysmindset.security/.KysDeviceAdminReceiver";

export function DeviceOwnerPanel() {
  const android = isAndroidApp();
  const [st, setSt] = useState<OwnerStatus | null>(null);

  const refresh = useCallback(() => {
    setSt(readOwnerStatus());
  }, []);

  useEffect(() => {
    refresh();
    const id = window.setInterval(refresh, 2500);
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
        title="Device Owner"
        subtitle="Make Kysmindset the device lock, not just an app overlay"
      />
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <Badge tone={st?.owner ? "emerald" : st?.admin ? "amber" : "muted"}>{role}</Badge>
        {st?.lockTask ? <Badge tone="cyan">Lock-task ready</Badge> : null}
        {st?.keyguardOff ? <Badge tone="emerald">System keyguard off</Badge> : null}
      </div>

      {!android ? (
        <p className="text-xs text-muted">
          Device Owner only works in the Android APK. Install the latest release, then come back
          here.
        </p>
      ) : st?.owner ? (
        <div className="space-y-3">
          <p className="text-xs text-muted">
            This phone treats Kysmindset as the device policy controller. While the lock screen is
            up, Home and Recents are blocked. Unlocking with your PIN returns the phone to normal.
          </p>
          <ToggleRow
            title="Replace system keyguard"
            desc="Turns the Android lock screen off so only the Kysmindset PIN is shown. Fails if the phone still has its own PIN or pattern — remove that first in system Settings."
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
      ) : (
        <div className="space-y-3">
          <p className="text-xs text-muted">
            Android will not let an app appoint itself Device Owner after you have signed in.
            Two steps: enable device admin here, then run the ADB command from a computer while
            the phone has <span className="text-fg">no accounts</span> (or after a factory reset).
          </p>
          <div className="flex flex-wrap gap-2">
            <Button size="sm" onClick={() => requestAndroidAdmin()}>
              Enable device admin
            </Button>
            <Button
              size="sm"
              variant="outline"
              onClick={() => {
                void navigator.clipboard.writeText(ADB).then(
                  () => toast.success("ADB command copied"),
                  () => toast.message(ADB),
                );
              }}
            >
              <Copy className="size-3.5" />
              Copy ADB command
            </Button>
          </div>
          <pre className="overflow-x-auto rounded-md border border-line bg-elevated px-3 py-2 font-mono text-2xs text-cyan">
            {ADB}
          </pre>
          <ol className="list-decimal space-y-1 pl-4 text-xs text-muted">
            <li>Install this APK and open it once.</li>
            <li>USB debugging on. No Google/Samsung accounts, or the command fails.</li>
            <li>Run the command. Success looks like: Device owner set to package app.kysmindset.security</li>
            <li>Reopen Config. Role should read Device owner.</li>
          </ol>
        </div>
      )}
    </Panel>
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
