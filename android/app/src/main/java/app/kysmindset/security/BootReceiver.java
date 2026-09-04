package app.kysmindset.security;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
            && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)
            && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            return;
        }
        if (LockGateService.deviceLockOn(context) && DeviceOwner.isAdmin(context)) {
            Intent svc = new Intent(context, LockGateService.class);
            svc.setAction(LockGateService.ACTION_START);
            startFg(context, svc);
        }
        if (!KillVpnService.shouldRestore(context)) return;
        Intent prep = VpnService.prepare(context);
        if (prep != null) return;
        startFg(context, new Intent(context, KillVpnService.class));
    }

    private static void startFg(Context context, Intent svc) {
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
            else context.startService(svc);
        } catch (Exception ignored) {
        }
    }
}
