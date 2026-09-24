import { useI18n } from "../../lib/i18n";
import type { ConnectionStatus } from "../../lib/game/connection";
import styles from "./game.module.css";

/** Phone banner: "Reconnecting…", then after 20 seconds "No connection" with a retry button. */
export function ConnectionBanner({ status, onRetry }: { status: ConnectionStatus; onRetry: () => void }) {
  const { t } = useI18n();
  if (status === "reconnecting") {
    return (
      <div className={styles.banner} role="status">
        {t("banner.reconnecting")}
      </div>
    );
  }
  if (status === "offline") {
    return (
      <div className={`${styles.banner} ${styles.bannerBad}`} role="alert">
        <span>{t("banner.offline")}</span>
        <button type="button" onClick={onRetry}>
          {t("common.retry")}
        </button>
      </div>
    );
  }
  return null;
}
