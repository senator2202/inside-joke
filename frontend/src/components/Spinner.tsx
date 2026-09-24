import { useI18n } from "../lib/i18n";
import styles from "./Spinner.module.css";

export function Spinner({ label, size = 22 }: { label?: string; size?: number }) {
  const { t } = useI18n();
  label ??= t("common.loading");
  return (
    <span className={styles.spinner} role="status" style={{ width: size, height: size }}>
      <span className="visually-hidden">{label}</span>
    </span>
  );
}
