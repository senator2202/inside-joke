import { Button } from "../../../components/Button";
import { useI18n } from "../../../lib/i18n";
import styles from "../Play.module.css";
import { QuickRating } from "./QuickRating";
import { type PhoneProps } from "./shared";

export function FinalePhone({
  state,
  request,
  rated,
  onRated,
  onHostYourOwn,
}: PhoneProps & { rated: boolean; onRated: () => void; onHostYourOwn: () => void }) {
  const { t, tp, place } = useI18n();
  const finale = state.finale;
  const author = finale?.standings.find((p) => p.id === finale.answerOfNightAuthorId);
  return (
    <div className={styles.scene}>
      <p className={styles.kicker}>{t("phone.yourTitle")}</p>
      <h1 className={styles.bigTitle}>{state.you.title ?? t("common.preparingAwards")}</h1>
      {state.you.rank !== undefined && (
        <p className={styles.lead}>{tp("phone.place", state.you.score ?? 0, { place: place(state.you.rank) })}</p>
      )}
      {finale?.answerOfNight && (
        <div className={styles.aotn}>
          <p className={styles.kicker}>
            {t("common.answerOfNight")}
            {author ? ` · ${author.name}` : ""}
          </p>
          <p>{finale.answerOfNight}</p>
        </div>
      )}
      <QuickRating
        rated={rated}
        onRate={(score) => {
          onRated();
          void request("feedback.quick", { score }).catch(() => undefined);
        }}
      />
      <Button variant="secondary" onClick={onHostYourOwn}>
        {t("common.hostYourOwn")}
      </Button>
    </div>
  );
}
