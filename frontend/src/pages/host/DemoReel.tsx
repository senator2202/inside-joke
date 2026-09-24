import { useI18n } from "../../lib/i18n";
import styles from "./DemoReel.module.css";

const GUESTS = ["🦊", "🐙", "🦄", "🐸", "🦉"];

/**
 * A 16-second loop of a real game's four beats, drawn live in CSS: friends join, the host uses a secret, a duel is voted,
 * a wipeout lands. With reduced motion only the first frame shows, which doubles as the poster.
 */
export function DemoReel() {
  const { t } = useI18n();
  return (
    <figure className={styles.tv} aria-label={t("demo.aria")}>
      <div className={styles.screen} aria-hidden="true">
        <div className={`${styles.scene} ${styles.s1}`}>
          <p className={styles.small}>{t("demo.scan")}</p>
          <p className={styles.code}>KWMP</p>
          <div className={styles.guests}>
            {GUESTS.map((g, i) => (
              <span key={g} style={{ animationDelay: `${0.3 + i * 0.35}s` }}>
                {g}
              </span>
            ))}
          </div>
        </div>
        <div className={`${styles.scene} ${styles.s2}`}>
          <p className={styles.host}>
            <span>🎙️</span> {t("demo.host")}
          </p>
          <p className={styles.small}>{t("demo.round")}</p>
        </div>
        <div className={`${styles.scene} ${styles.s3}`}>
          <p className={styles.prompt}>{t("demo.prompt")}</p>
          <div className={styles.answers}>
            <span>
              <b>A</b> {t("demo.answerA")}
            </span>
            <span>
              <b>B</b> {t("demo.answerB")}
            </span>
          </div>
          <div className={styles.guests}>
            {GUESTS.slice(2).map((g) => (
              <span key={g}>{g}</span>
            ))}
          </div>
        </div>
        <div className={`${styles.scene} ${styles.s4}`}>
          <p className={styles.wipeout}>{t("common.wipeout")}</p>
          <p className={styles.points}>{t("demo.points")}</p>
          <p className={styles.small}>{t("demo.allVoted")}</p>
        </div>
      </div>
      <figcaption className={styles.caption}>{t("demo.caption")}</figcaption>
    </figure>
  );
}
