import { Avatar } from "../../../components/game/Avatar";
import { Countdown } from "../../../components/game/Countdown";
import { HostLine } from "../../../components/game/HostLine";
import { KIND_ICONS, KIND_ORDER } from "../../../lib/game/labels";
import { useI18n } from "../../../lib/i18n";
import styles from "../Screen.module.css";
import { Scoreboard } from "./Scoreboard";
import { type SceneProps, playerById } from "./shared";

export function RoundVoteScene({ state, offset }: SceneProps) {
  const { t } = useI18n();
  const round = state.round;
  const voters = state.kindVote?.voters;
  const nobody = voters && Object.values(voters).every((v) => v.length === 0);
  return (
    <div className={styles.center}>
      <div className={styles.topRow}>
        <h1 className={styles.headline}>{t("roundVote.headline", { n: round?.n ?? 1, of: round?.of ?? 5 })}</h1>
        {!state.thinking && <Countdown deadline={state.deadline} offset={offset} size="huge" />}
      </div>
      <div className={styles.kinds}>
        {KIND_ORDER.map((kind) => (
          <div key={kind} className={styles.kindCard} data-chosen={state.thinking && round?.kind === kind ? true : undefined}>
            <span className={styles.kindIcon} aria-hidden="true">
              {KIND_ICONS[kind]}
            </span>
            <h2>{t(`kind.${kind}.title`)}</h2>
            <p>{t(`kind.${kind}.blurb`)}</p>
            <div className={styles.kindVoters}>
              {(voters?.[kind] ?? []).map((id) => {
                const p = playerById(state, id);
                return p ? <Avatar key={id} player={p} size="small" /> : null;
              })}
            </div>
          </div>
        ))}
      </div>
      {state.thinking ? <HostLine thinking /> : nobody ? <p className={styles.hint}>{t("roundVote.hostChooses")}</p> : null}
      <Scoreboard players={state.players ?? []} compact />
    </div>
  );
}

// ---------------------------------------------------------------- S4 answering
