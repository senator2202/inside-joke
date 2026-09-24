import { useI18n } from "../../lib/i18n";
import styles from "./game.module.css";

/** The AI host's current line. While the AI is working the screen shows "typing" (never longer than 5 s). */
export function HostLine({
  text,
  thinking,
  skipped,
  speaking,
  size = "normal",
}: {
  text?: string;
  thinking?: boolean;
  skipped?: boolean;
  speaking?: boolean;
  size?: "normal" | "large";
}) {
  const { t } = useI18n();
  if (thinking) {
    return (
      <p className={`${styles.host} ${styles.typing}`} role="status">
        <span className={styles.hostMark} aria-hidden="true">
          🎙️
        </span>
        <span>{t("host.typing")}</span>
        <span className={styles.dots} aria-hidden="true">
          <i />
          <i />
          <i />
        </span>
      </p>
    );
  }
  if (!text) return null;
  return (
    <p
      className={`${styles.host} ${size === "large" ? styles.hostLarge : ""}`}
      data-skipped={skipped || undefined}
      data-speaking={speaking || undefined}
      aria-live="polite"
    >
      <span className={styles.hostMark} aria-hidden="true">
        🎙️
      </span>
      <span>{text}</span>
    </p>
  );
}
