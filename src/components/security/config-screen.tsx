import { Component, type ErrorInfo, type ReactNode } from "react";
import { AppUpdatePanel } from "./app-update";
import { ConfigPanel } from "./config-panel";
import { DeviceOwnerPanel } from "./device-owner";
import { KillAllowPanel } from "./kill-allow";

class PanelGuard extends Component<{ name: string; children: ReactNode }, { err: string | null }> {
  state = { err: null as string | null };
  static getDerivedStateFromError(error: Error) {
    return { err: error.message };
  }
  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("[kysmindset] panel", this.props.name, error, info.componentStack);
  }
  render() {
    if (!this.state.err) return this.props.children;
    return (
      <section className="rounded-xl border border-line bg-surface p-5">
        <p className="text-sm text-fg">{this.props.name} failed to load.</p>
        <p className="mt-1 text-xs text-muted">{this.state.err}</p>
      </section>
    );
  }
}

export function ConfigScreen() {
  return (
    <div className="space-y-4">
      <PanelGuard name="App update">
        <AppUpdatePanel />
      </PanelGuard>
      <PanelGuard name="Device lock">
        <DeviceOwnerPanel />
      </PanelGuard>
      <PanelGuard name="Do not block">
        <KillAllowPanel />
      </PanelGuard>
      <PanelGuard name="Settings">
        <ConfigPanel />
      </PanelGuard>
    </div>
  );
}
