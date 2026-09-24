import { Link } from "react-router";
import { useI18n } from "../lib/i18n";
import styles from "./Logo.module.css";

/** Wordmark: two redaction bars (the secret) next to the name. */
export function Logo({ to = "/", size = "normal" }: { to?: string | null; size?: "normal" | "large" }) {
  const { t } = useI18n();
  const mark = (
    <span className={`${styles.logo} ${size === "large" ? styles.large : ""}`}>
      <svg className={styles.mark} viewBox="0 0 64 64" aria-hidden="true">
        <rect width="64" height="64" rx="16" fill="#231942" />
        <rect x="12" y="20" width="40" height="9" rx="2" fill="#F5E663" />
        <rect x="12" y="35" width="28" height="9" rx="2" fill="#FF7AA2" />
      </svg>
      <span className={styles.name}>Inside Joke</span>
    </span>
  );
  if (to === null) return mark;
  return (
    <Link to={to} className={styles.link} aria-label={t("logo.home")}>
      {mark}
    </Link>
  );
}
