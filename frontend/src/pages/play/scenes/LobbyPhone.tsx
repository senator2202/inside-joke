import { Button } from "../../../components/Button";
import { Avatar } from "../../../components/game/Avatar";
import { useI18n } from "../../../lib/i18n";
import styles from "../Play.module.css";
import { type PhoneProps } from "./shared";

export function LobbyPhone({ state, onSecret }: PhoneProps & { onSecret?: () => void }) {
  const { t } = useI18n();
  const players = state.players ?? [];
  const captain = state.you.role === "CAPTAIN";
  return (
    <div className={styles.scene}>
      <h1 className={styles.bigTitle}>{t("phone.youreIn", { name: state.you.name ?? "" })}</h1>
      {players.length === 1 && <p className={styles.lead}>{t("phone.first")}</p>}
      {state.starting || state.paywall ? (
        <p className={styles.lead}>{captain && state.paywall ? t("phone.hostPicking") : t("phone.oneSecond")}</p>
      ) : (
        <p className={styles.lead}>{captain ? t("phone.captain") : t("phone.waitCaptain")}</p>
      )}
      <ul className={styles.roster}>
        {players.map((p) => (
          <li key={p.id}>
            <Avatar player={p} size="small" /> {p.name}
          </li>
        ))}
      </ul>
      {onSecret ? (
        <Button variant="secondary" onClick={onSecret}>
          {t("phone.spillSecret")}
        </Button>
      ) : (
        <p className={styles.small}>{t("phone.secretsOff")}</p>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- P3 intake
