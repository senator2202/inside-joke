import { Button } from "../../../components/Button";
import { useI18n } from "../../../lib/i18n";
import styles from "../Play.module.css";

export function Waiting({ text, onSecret }: { text: string; onSecret?: () => void }) {
  const { t } = useI18n();
  return (
    <div className={styles.scene}>
      <p className={styles.bigTitle}>{text}</p>
      {onSecret && (
        <Button variant="secondary" onClick={onSecret}>
          {t("phone.secretButton")}
        </Button>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- P5 round kind
