import { Component, useEffect, type ErrorInfo, type ReactNode } from "react";
import { Dashboard } from "@/components/security/dashboard";
import { LockScreen } from "@/components/security/lock-screen";
import { useSecurity } from "@/lib/security/store";

export function App() {
  const hydrated = useSecurity((s) => s.hydrated);
  const unlocked = useSecurity((s) => s.unlocked);
  const setHydrated = useSecurity((s) => s.setHydrated);

  useEffect(() => {
    setHydrated();
    const t = window.setTimeout(() => {
      if (!useSecurity.getState().hydrated) {
        useSecurity.setState({ hydrated: true });
      }
    }, 1800);
    return () => window.clearTimeout(t);
  }, [setHydrated]);

  if (!hydrated) {
    return (
      <div className="flex min-h-dvh items-center justify-center bg-bg">
        <div className="size-8 animate-spin rounded-full border-4 border-elevated border-t-cyan" />
      </div>
    );
  }

  return (
    <BootError>{unlocked ? <Dashboard /> : <LockScreen />}</BootError>
  );
}

class BootError extends Component<{ children: ReactNode }, { err: string | null }> {
  state = { err: null as string | null };
  static getDerivedStateFromError(error: Error) {
    return { err: error.message };
  }
  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error("[kysmindset] render", error, info.componentStack);
  }
  render() {
    if (!this.state.err) return this.props.children;
    return (
      <div className="flex min-h-dvh flex-col items-center justify-center gap-3 bg-bg px-6 text-center">
        <p className="text-sm text-fg">Kysmindset hit a snag loading.</p>
        <p className="max-w-sm text-xs text-muted">{this.state.err}</p>
        <button
          type="button"
          className="rounded-md border border-line bg-elevated px-3 py-2 text-sm text-fg"
          onClick={() => {
            try {
              localStorage.removeItem("kysmindset-v1");
              sessionStorage.removeItem("kysmindset_unlocked");
            } catch {
              /* ignore */
            }
            window.location.reload();
          }}
        >
          Reset and reload
        </button>
      </div>
    );
  }
}
