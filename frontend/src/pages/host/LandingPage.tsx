import { useEffect, useState } from "react";
import { Link, useSearchParams } from "react-router";
import { Button, ButtonLink } from "../../components/Button";
import { HostLayout } from "../../components/HostLayout";
import { Notice } from "../../components/Notice";
import { track } from "../../lib/analytics";
import { api } from "../../lib/api";
import { useI18n } from "../../lib/i18n";
import { DemoReel } from "./DemoReel";
import styles from "./LandingPage.module.css";

function cleanRef(raw: string | null): string | undefined {
  const ref = raw?.replace(/[^A-Za-z0-9_-]/g, "").slice(0, 32);
  return ref || undefined;
}

/** H1: explain the idea in five seconds and split the flows: hosts create a party, guests enter a code. */
export function LandingPage() {
  const [params] = useSearchParams();
  const { t } = useI18n();
  const [drain, setDrain] = useState(false);
  const steps = [1, 2, 3].map((n) => ({
    n: String(n),
    title: t(`landing.step${n as 1 | 2 | 3}.title`),
    text: t(`landing.step${n as 1 | 2 | 3}.text`),
  }));
  const lines = [
    { tone: t("tone.FAMILY.title"), text: t("landing.line1") },
    { tone: t("tone.CHEEKY.title"), text: t("landing.line2") },
    { tone: t("tone.SPICY.title"), text: t("landing.line3") },
  ];
  const faq = ([1, 2, 3, 4] as const).map((n) => ({ q: t(`landing.q${n}`), a: t(`landing.a${n}`) }));
  const deleted = params.get("deleted") === "1";
  const ref = cleanRef(params.get("ref"));

  useEffect(() => {
    track("landing_viewed", ref ? { ref } : {});
  }, [ref]);

  useEffect(() => {
    const ctl = new AbortController();
    api<{ drainMode: boolean }>("/api/status", { signal: ctl.signal }).then(
      (s) => setDrain(s.drainMode),
      () => undefined,
    );
    return () => ctl.abort();
  }, []);

  return (
    <HostLayout>
      {deleted && (
        <Notice tone="success" title={t("landing.deletedTitle")}>
          {t("landing.deletedText")}
        </Notice>
      )}
      {drain && (
        <Notice tone="error" title={t("landing.drainTitle")}>
          {t("landing.drainText")}
        </Notice>
      )}

      <section className={styles.hero}>
        <div className={styles.pitch}>
          <h1 className={styles.promise}>
            {t("landing.promiseBefore")}
            <mark>{t("landing.promiseMark")}</mark>
            {t("landing.promiseAfter")}
          </h1>
          <p className={styles.lead}>{t("landing.lead")}</p>
          <div className={styles.ctas}>
            {drain ? (
              <Button big disabled className={styles.create}>
                {t("landing.create")}
              </Button>
            ) : (
              <ButtonLink to="/new" big className={styles.create}>
                {t("landing.create")}
              </ButtonLink>
            )}
            <ButtonLink to="/join" big variant="secondary" className={styles.join}>
              {t("landing.join")}
            </ButtonLink>
          </div>
          <p className={styles.fine}>{t("landing.fine")}</p>
        </div>
        <DemoReel />
      </section>

      <section className={styles.section} aria-labelledby="how">
        <h2 id="how">{t("landing.how")}</h2>
        <ol className={styles.steps}>
          {steps.map((s) => (
            <li key={s.n}>
              <span className={styles.stepN} aria-hidden="true">
                {s.n}
              </span>
              <h3>{s.title}</h3>
              <p>{s.text}</p>
            </li>
          ))}
        </ol>
      </section>

      <section className={styles.section} aria-labelledby="lines">
        <h2 id="lines">{t("landing.notes")}</h2>
        <div className={styles.lines}>
          {lines.map((l) => (
            <figure key={l.tone} className={styles.line}>
              <blockquote>{l.text}</blockquote>
              <figcaption>{l.tone}</figcaption>
            </figure>
          ))}
        </div>
      </section>

      <section className={styles.section} aria-labelledby="prices">
        <h2 id="prices">{t("landing.prices")}</h2>
        <div className={styles.prices}>
          <div className={styles.price}>
            <h3>{t("landing.freeTitle")}</h3>
            <p className={styles.amount}>{t("landing.freeAmount")}</p>
            <p>{t("landing.freeText")}</p>
          </div>
          <div className={styles.price} data-featured>
            <h3>Party Pass</h3>
            <p className={styles.amount}>$2.99</p>
            <p>{t("landing.partyText")}</p>
          </div>
          <div className={styles.price}>
            <h3>Host Pass</h3>
            <p className={styles.amount}>
              $14.99<span>{t("pass.perYear")}</span>
            </p>
            <p>{t("landing.hostText")}</p>
          </div>
        </div>
        <p className={styles.fine}>{t("landing.pricesFine")}</p>
      </section>

      <section className={`${styles.section} ${styles.streamers}`} aria-labelledby="stream">
        <h2 id="stream">{t("landing.streamers")}</h2>
        <p>{t("landing.streamersText")}</p>
      </section>

      <section className={styles.section} aria-labelledby="faq">
        <h2 id="faq">{t("landing.questions")}</h2>
        <div className={styles.faq}>
          {faq.map((f) => (
            <details key={f.q}>
              <summary>{f.q}</summary>
              <p>{f.a}</p>
            </details>
          ))}
        </div>
        <p className={styles.fine}>
          {t("landing.moreIn")}
          <a href="/privacy.html">{t("landing.privacyLink")}</a>
          {t("common.and")}
          <a href="/terms.html">{t("landing.termsLink")}</a>. <Link to="/join">{t("landing.haveCode")}</Link>
        </p>
      </section>
    </HostLayout>
  );
}
