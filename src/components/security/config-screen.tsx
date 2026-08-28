import { ConfigPanel } from "./panels";
import { KillAllowPanel } from "./kill-allow";

export function ConfigScreen() {
  return (
    <div className="space-y-4">
      <KillAllowPanel />
      <ConfigPanel />
    </div>
  );
}
