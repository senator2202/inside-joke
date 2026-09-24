import { Button } from "../../../components/Button";
import { useI18n } from "../../../lib/i18n";
import styles from "../Play.module.css";

export function SecretInvite({ onSecret, onWait }: { onSecret: () => void; onWait: () => void }) {
  const { t } = useI18n();
  return (
    <div className={styles.scene}>
      <h1 className={styles.bigTitle}>{t("phone.inviteTitle")}</h1>
      <ul className={styles.examples}>
        <li>{t("phone.inviteEx1")}</li>
        <li>{t("phone.inviteEx2")}</li>
        <li>{t("phone.inviteEx3")}</li>
      </ul>
      <Button big block onClick={onSecret}>
        {t("phone.spillSecretPlain")}
      </Button>
      <Button variant="quiet" onClick={onWait}>
        {t("phone.illWait")}
      </Button>
    </div>
  );
}
