import { Countdown } from "../../../components/game/Countdown";
import { KIND_ICONS, KIND_ORDER } from "../../../lib/game/labels";
import { useI18n } from "../../../lib/i18n";
import styles from "../Play.module.css";
import { type PhoneProps } from "./shared";

export function KindVotePhone({ state, offset, request }: PhoneProps) {
  const { t } = useI18n();
  const mine = state.kindVote?.myKind;
  const voters = state.kindVote?.voters;
  return (
    <div className={styles.scene}>
      <div className={styles.row}>
        <p className={styles.kicker}>{t("common.roundOf", { n: state.round?.n ?? 1, of: state.round?.of ?? 5 })}</p>
        <Countdown deadline={state.deadline} offset={offset} />
      </div>
      <h1 className={styles.title}>{mine ? t("phone.yourVote", { kind: t(`kind.${mine}.title`) }) : t("phone.whatsNext")}</h1>
      <div className={styles.kindList}>
        {KIND_ORDER.map((kind) => (
          <button
            key={kind}
            type="button"
            className={styles.kindButton}
            aria-pressed={mine === kind}
            onClick={() => void request("round.kind.vote", { kind }).catch(() => undefined)}
          >
            <span className={styles.kindIcon} aria-hidden="true">
              {KIND_ICONS[kind]}
            </span>
            <span className={styles.kindText}>
              <strong>{t(`kind.${kind}.title`)}</strong>
              <span>{t(`kind.${kind}.blurb`)}</span>
            </span>
            {mine && <span className={styles.kindCount}>{voters?.[kind]?.length ?? 0}</span>}
          </button>
        ))}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- P6 answering
