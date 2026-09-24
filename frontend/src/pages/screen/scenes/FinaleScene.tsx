import { Button } from "../../../components/Button";
import { Avatar } from "../../../components/game/Avatar";
import { HostLine } from "../../../components/game/HostLine";
import { useI18n } from "../../../lib/i18n";
import styles from "../Screen.module.css";
import { type SceneProps } from "./shared";

export function FinaleScene({ state, owner, send, speaking }: SceneProps) {
  const { t } = useI18n();
  const finale = state.finale;
  if (!finale?.ready) {
    return (
      <div className={styles.center}>
        <h1 className={styles.headline}>{t("common.preparingAwards")}</h1>
        <HostLine thinking />
      </div>
    );
  }
  const winners = finale.standings.filter((p) => finale.winnerIds.includes(p.id));
  const author = finale.standings.find((p) => p.id === finale.answerOfNightAuthorId);
  return (
    <div className={styles.finale}>
      <section className={styles.winner}>
        <p className={styles.kicker}>{winners.length > 1 ? t("finale.winners") : t("finale.winner")}</p>
        {winners.map((w) => (
          <div key={w.id} className={styles.winnerCard}>
            <Avatar player={w} size="large" />
            <h1>{w.name}</h1>
            <p>{finale.titles[w.id]}</p>
          </div>
        ))}
        {winners.length === 0 && <h1>{t("finale.allZero")}</h1>}
      </section>
      <section>
        <ol className={styles.standings}>
          {finale.standings.map((p) => (
            <li key={p.id}>
              <span className={styles.rank}>{p.rank}</span>
              <Avatar player={p} size="small" />
              <span className={styles.scoreName}>{p.name}</span>
              <span className={styles.title}>{finale.titles[p.id]}</span>
              <span className={styles.scoreValue}>{p.score}</span>
            </li>
          ))}
        </ol>
        {finale.answerOfNight && (
          <div className={styles.aotn}>
            <p className={styles.kicker}>
              {t("common.answerOfNight")}
              {author ? ` \u00b7 ${author.name}` : ""}
            </p>
            <p className={styles.aotnPrompt}>{finale.answerOfNightPrompt}</p>
            <p className={styles.aotnText}>{finale.answerOfNight}</p>
          </div>
        )}
        <HostLine text={finale.speech} speaking={speaking} />
        {owner && (
          <div className={styles.actions}>
            <Button big onClick={() => send("game.again")}>
              {t("common.playAgain")}
            </Button>
            <Button variant="secondary" onClick={() => send("room.close")}>
              {t("finale.endParty")}
            </Button>
          </div>
        )}
      </section>
    </div>
  );
}
