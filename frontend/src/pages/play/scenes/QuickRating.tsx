import { useI18n } from "../../../lib/i18n";
import styles from "../Play.module.css";

export function QuickRating({ rated, onRate }: { rated: boolean; onRate: (score: number) => void }) {
  const { t } = useI18n();
  if (rated) return <p className={styles.lead}>{t("phone.thanksRating")}</p>;
  return (
    <div className={styles.quick} role="group" aria-label={t("phone.howWasIt")}>
      {[
        ["😂", 3],
        ["🙂", 2],
        ["😐", 1],
      ].map(([face, score]) => (
        <button key={score} type="button" aria-label={t("phone.rateAria", { score: String(score) })} onClick={() => onRate(Number(score))}>
          {face}
        </button>
      ))}
    </div>
  );
}

// ---------------------------------------------------------------- P4 secret sheet
