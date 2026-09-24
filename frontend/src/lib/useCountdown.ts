import { useEffect, useState } from "react";

/**
 * Whole seconds left until a wall-clock deadline (ms). Re-renders four times a second while running.
 * `now` lets callers correct for the offset between the server clock and this device.
 */
export function useCountdown(deadlineMs: number | null, now: () => number = Date.now): number {
  const [, setTick] = useState(0);

  useEffect(() => {
    if (deadlineMs === null) return undefined;
    const id = window.setInterval(() => {
      setTick((n) => n + 1);
      if (now() >= deadlineMs) window.clearInterval(id);
    }, 250);
    return () => window.clearInterval(id);
  }, [deadlineMs, now]);

  return deadlineMs === null ? 0 : Math.max(0, Math.ceil((deadlineMs - now()) / 1000));
}
