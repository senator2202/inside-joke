import { Button } from "../../../components/Button";
import { Avatar } from "../../../components/game/Avatar";
import { Countdown } from "../../../components/game/Countdown";
import { HostLine } from "../../../components/game/HostLine";
import { useI18n } from "../../../lib/i18n";
import styles from "../Screen.module.css";
import { type SceneProps } from "./shared";

export function IntakeScene({ state, offset, owner, send }: SceneProps) {
  const { t } = useI18n();
  const players = state.players ?? [];
  const done = players.filter((p) => p.status === "done").length;
  return (
    <div className={styles.center}>
      <div className={styles.topRow}>
        <h1 className={styles.headline}>{t("intake.headline")}</h1>
        <Countdown deadline={state.deadline} offset={offset} size="huge" />
      </div>
      <ul className={styles.progressGrid}>
        {players.map((p) => (
          <li key={p.id}>
            <Avatar player={p} size="large" badge={p.status === "done" ? "✓" : t("intake.writing")} />
            <span>{p.name}</span>
          </li>
        ))}
      </ul>
      <p className={styles.hint}>{state.secretsOpen === false ? t("intake.hintNoSecrets") : t("intake.hint")}</p>
      {state.secretsOpen !== false && <p className={styles.secretsCount}>{t("common.secretsCollected", { n: state.secrets ?? 0 })}</p>}
      <HostLine text={state.host?.text} thinking={state.thinking} />
      {owner && players.length > 0 && done >= players.length - 1 && done < players.length && (
        <Button variant="secondary" onClick={() => send("game.next")}>
          {t("intake.dontWait")}
        </Button>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- S3 round kind vote
