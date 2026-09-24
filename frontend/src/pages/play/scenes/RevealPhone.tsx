import { Button } from "../../../components/Button";
import { useI18n } from "../../../lib/i18n";
import styles from "../Play.module.css";
import { type PhoneProps } from "./shared";

export function RevealPhone({ state, request }: PhoneProps) {
  const { t, tp, placeIn } = useI18n();
  const you = state.you;
  const round = state.round;
  const myOption = round?.options?.find((o) => o.authorId === you.playerId);
  const wipeout = round?.landslide && myOption?.winner;
  return (
    <div className={styles.scene}>
      <p className={styles.bigTitle}>{t("phone.lookScreen")}</p>
      {wipeout ? (
        <p className={styles.result}>{t("common.wipeout")}</p>
      ) : you.roundPoints ? (
        <p className={styles.result}>+{you.roundPoints}</p>
      ) : null}
      {you.rank !== undefined && <p className={styles.lead}>{tp("phone.rank", you.score ?? 0, { rank: placeIn(you.rank) })}</p>}
      {state.host?.canSkip && state.host.lineId && (
        <Button variant="secondary" onClick={() => void request("line.skip", { lineId: state.host!.lineId }).catch(() => undefined)}>
          {t("phone.skipLine")}
        </Button>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- P9 finale
