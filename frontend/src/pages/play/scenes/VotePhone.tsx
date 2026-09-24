import { useState } from "react";
import { Avatar } from "../../../components/game/Avatar";
import { Countdown } from "../../../components/game/Countdown";
import { useI18n } from "../../../lib/i18n";
import styles from "../Play.module.css";
import { type PhoneProps, codeOf } from "./shared";

export function VotePhone({ state, offset, request }: PhoneProps) {
  const i18n = useI18n();
  const { t } = i18n;
  const round = state.round;
  const you = state.you;
  const [picked, setPicked] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const stepKey = `${round?.n}/${round?.duelIndex ?? 0}`;
  const [forStep, setForStep] = useState(stepKey);
  if (forStep !== stepKey) {
    setForStep(stepKey);
    setPicked(null);
  }
  if (!round) return null;
  const header = (
    <div className={styles.row}>
      <p className={styles.kicker}>{t(`kind.${round.kind!}.title`)}</p>
      <Countdown deadline={state.deadline} offset={offset} />
    </div>
  );
  if (you.inDuel)
    return (
      <div className={styles.scene}>
        {header}
        <p className={styles.bigTitle}>{t("phone.yourDuel")}</p>
      </div>
    );
  if (you.subject)
    return (
      <div className={styles.scene}>
        {header}
        <p className={styles.bigTitle}>{t("phone.aboutYou")}</p>
      </div>
    );
  if (you.voted || picked)
    return (
      <div className={styles.scene}>
        {header}
        <p className={styles.bigTitle}>{t("common.voteAccepted")}</p>
      </div>
    );
  if (!you.canVote)
    return (
      <div className={styles.scene}>
        {header}
        <p className={styles.bigTitle}>{t("phone.watchScreen")}</p>
      </div>
    );

  const vote = (optionId: string) => {
    setPicked(optionId);
    request("vote.submit", { optionId }).catch((e: unknown) => {
      if (codeOf(e) === "INVALID_PHASE") return;
      setPicked(null);
      setError(i18n.error(codeOf(e)));
    });
  };
  return (
    <div className={styles.scene}>
      {header}
      <p className={styles.question}>{round.prompt ?? round.question ?? round.statement}</p>
      {round.kind === "WHO_OF_US" ? (
        <div className={styles.whoGrid}>
          {(round.options ?? []).map((o) => {
            const p = state.players?.find((x) => x.id === o.id);
            return (
              <button key={o.id} type="button" className={styles.whoButton} onClick={() => vote(o.id)}>
                {p && <Avatar player={p} />}
                <span>{o.text}</span>
              </button>
            );
          })}
        </div>
      ) : (
        <div className={styles.options}>
          {(round.options ?? []).map((o) => (
            <button key={o.id} type="button" className={styles.option} onClick={() => vote(o.id)}>
              {round.kind === "ANSWER_DUEL" && <span className={styles.optionLetter}>{o.id}</span>}
              {o.text}
            </button>
          ))}
        </div>
      )}
      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- P8 reveal
