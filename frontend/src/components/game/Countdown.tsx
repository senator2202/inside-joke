import { useCallback } from "react";
import { useI18n } from "../../lib/i18n";
import { useCountdown } from "../../lib/useCountdown";
import styles from "./game.module.css";

/** Seconds left until the server's deadline, corrected for this device's clock offset. */
export function Countdown({ deadline, offset, size = "normal" }: { deadline?: number; offset: number; size?: "normal" | "huge" }) {
  const { tp } = useI18n();
  const now = useCallback(() => Date.now() + offset, [offset]);
  const seconds = useCountdown(deadline ?? null, now);
  if (deadline === undefined) return null;
  return (
    <span
      className={`${styles.timer} ${size === "huge" ? styles.timerHuge : ""}`}
      data-urgent={seconds <= 5 || undefined}
      role="timer"
      aria-label={tp("timer.left", seconds)}
    >
      {seconds}
    </span>
  );
}

/** The same countdown as a number, for auto-submitting drafts when time runs out. */
export function useSecondsLeft(deadline: number | undefined, offset: number): number {
  const now = useCallback(() => Date.now() + offset, [offset]);
  return useCountdown(deadline ?? null, now);
}
