import { useEffect, useRef, useState } from "react";
import { isApiError } from "../../lib/api";
import { track } from "../../lib/analytics";
import {
  PRODUCT_NAMES,
  fetchAccess,
  fetchCheckout,
  formatPassEnd,
  listPrice,
  type CheckoutConfig,
  type Pass,
  type ProductId,
} from "../../lib/billing";
import { loadPaddle, type PaddleApi } from "../../lib/paddle";
import { useI18n } from "../../lib/i18n";
import { Button } from "../Button";
import { Spinner } from "../Spinner";
import styles from "./PassPicker.module.css";

const TITLES = {
  PAYWALL_FREE_LIMIT: "pass.titleFree",
  PAYWALL_MONTHLY_LIMIT: "pass.titleMonthly",
  BUDGET_PAUSED: "pass.titleBudget",
} as const;

const BLURBS = { PARTY_PASS: "pass.blurbParty", HOST_PASS: "pass.blurbHost" } as const;

const POLL_EVERY_MS = 2_000;
const SLOW_AFTER_MS = 30_000;
const GIVE_UP_AFTER_MS = 5 * 60_000;
const START_DELAY_MS = 3_000;

type Step =
  | { kind: "loading" }
  | { kind: "unavailable" }
  | { kind: "signin" }
  | { kind: "blocked" }
  | { kind: "choose"; error?: string }
  | { kind: "paying"; product: ProductId }
  | { kind: "activating"; product: ProductId; slow: boolean; gaveUp: boolean }
  | { kind: "active"; pass: Pass };

export interface PassPickerProps {
  /** Paywall reason from the room; absent when opened from the account page. */
  reason?: string | null;
  waitingPlayers?: number;
  onDismiss: () => void;
  /** Called once the new pass is confirmed (after a short "Done!" moment in the party context). */
  onActivated: (pass: Pass) => void;
}

/** H4 pass picker, H5 Paddle overlay and H5b activation, as one dialog. */
export function PassPicker({ reason, waitingPlayers, onDismiss, onActivated }: PassPickerProps) {
  const i18n = useI18n();
  const { t, tp } = i18n;
  const [step, setStep] = useState<Step>({ kind: "loading" });
  const [config, setConfig] = useState<CheckoutConfig | null>(null);
  const [prices, setPrices] = useState<Record<string, string> | null>(null);
  const [adult, setAdult] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const paddle = useRef<PaddleApi | null>(null);
  const knownPasses = useRef<Set<string>>(new Set());
  const declined = useRef(false);
  const activated = useRef(onActivated);

  useEffect(() => {
    activated.current = onActivated;
  });

  // Load the checkout config, the passes we already have, and Paddle itself.
  useEffect(() => {
    const ctl = new AbortController();
    let unsubscribe = () => undefined as unknown;
    (async () => {
      const [cfg, access] = await Promise.all([fetchCheckout(ctl.signal), fetchAccess(ctl.signal)]);
      knownPasses.current = new Set(access.access.passes.map((p) => p.id));
      setConfig(cfg);
      if (!cfg.available || !cfg.clientToken) {
        setStep({ kind: "unavailable" });
        return;
      }
      const api = await loadPaddle({ clientToken: cfg.clientToken, environment: cfg.environment });
      if (ctl.signal.aborted) return;
      paddle.current = api;
      unsubscribe = api.onEvent((event) => {
        if (event.name === "checkout.payment.failed") {
          // Paddle shows the decline inside its overlay; if the host then closes it, say what happened.
          declined.current = true;
        } else if (event.name === "checkout.completed") {
          declined.current = false;
          setStep((c) => (c.kind === "paying" ? { kind: "activating", product: c.product, slow: false, gaveUp: false } : c));
        } else if (event.name === "checkout.closed") {
          const error = declined.current ? "declined" : undefined;
          declined.current = false;
          setStep((c) => (c.kind === "paying" ? { kind: "choose", error } : c));
        }
      });
      setStep({ kind: "choose" });
      api.previewPrices(cfg.offers.map((o) => o.priceId)).then(setPrices, () => setPrices({}));
    })().catch((e: unknown) => {
      if (e instanceof DOMException && e.name === "AbortError") return;
      if (isApiError(e) && e.status === 401) setStep({ kind: "signin" });
      else setStep(e instanceof Error && e.name === "PaddleUnavailable" ? { kind: "blocked" } : { kind: "unavailable" });
    });
    return () => {
      ctl.abort();
      unsubscribe();
    };
  }, [attempt]);

  // H5b: poll until the webhook has created the pass.
  const activating = step.kind === "activating" ? step.product : null;
  useEffect(() => {
    if (!activating) return;
    const started = Date.now();
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const access = await fetchAccess();
        const fresh = access.access.passes.find((p) => p.type === activating && !knownPasses.current.has(p.id));
        if (fresh && !stopped) {
          setStep({ kind: "active", pass: fresh });
          return;
        }
      } catch {
        /* keep polling through network hiccups */
      }
      if (stopped) return;
      const elapsed = Date.now() - started;
      if (elapsed >= GIVE_UP_AFTER_MS) {
        setStep({ kind: "activating", product: activating, slow: true, gaveUp: true });
        return;
      }
      if (elapsed >= SLOW_AFTER_MS) setStep((s) => (s.kind === "activating" && !s.slow ? { ...s, slow: true } : s));
      timer = setTimeout(() => void poll(), POLL_EVERY_MS);
    };
    timer = setTimeout(() => void poll(), POLL_EVERY_MS);
    return () => {
      stopped = true;
      clearTimeout(timer);
    };
  }, [activating]);

  const activePass = step.kind === "active" ? step.pass : null;
  useEffect(() => {
    if (!activePass) return;
    const t = setTimeout(() => activated.current(activePass), reason ? START_DELAY_MS : 1_500);
    return () => clearTimeout(t);
  }, [activePass, reason]);

  const buy = (product: ProductId) => {
    const offer = config?.offers.find((o) => o.product === product);
    if (!offer || !paddle.current || !config) return;
    track("checkout_opened", { product });
    setStep({ kind: "paying", product });
    paddle.current.openCheckout({ priceId: offer.priceId, email: config.email, customData: config.customData });
  };

  const offers = (config?.offers ?? []).filter((o) => !(reason === "PAYWALL_MONTHLY_LIMIT" && o.product === "HOST_PASS"));
  const support = config?.supportEmail ?? "support@insidejoke.app";

  return (
    <div className={styles.backdrop} role="presentation">
      <div className={styles.dialog} role="dialog" aria-modal="true" aria-labelledby="pass-title">
        {step.kind === "activating" || step.kind === "active" ? (
          <Activation step={step} party={!!reason} support={support} />
        ) : (
          <>
            <h2 id="pass-title" className={styles.title}>
              {reason && reason in TITLES ? t(TITLES[reason as keyof typeof TITLES]) : t("pass.getAPass")}
            </h2>
            {reason && waitingPlayers !== undefined && waitingPlayers > 0 && (
              <p className={styles.subtitle}>{tp("pass.waiting", waitingPlayers)}</p>
            )}
            {step.kind === "loading" && (
              <div className={styles.center}>
                <Spinner label={t("pass.loadingPrices")} />
              </div>
            )}
            {step.kind === "unavailable" && (
              <p className={styles.error} role="alert">
                {t("pass.unavailable")}
              </p>
            )}
            {step.kind === "signin" && (
              <div className={styles.center}>
                <p className={styles.error} role="alert">
                  {t("pass.expired")}
                </p>
                <a className={styles.link} href={`/login?returnTo=${encodeURIComponent(window.location.pathname)}`}>
                  {t("layout.signIn")}
                </a>
              </div>
            )}
            {step.kind === "blocked" && (
              <div className={styles.center}>
                <p className={styles.error} role="alert">
                  {t("pass.blocked")}
                </p>
                <Button
                  variant="secondary"
                  onClick={() => {
                    setStep({ kind: "loading" });
                    setAttempt((n) => n + 1);
                  }}
                >
                  {t("common.tryAgain")}
                </Button>
              </div>
            )}
            {(step.kind === "choose" || step.kind === "paying") && (
              <>
                <div className={styles.cards}>
                  {offers.map((o) => (
                    <div key={o.product} className={styles.card} data-recommended={o.product === "PARTY_PASS" || undefined}>
                      {o.product === "PARTY_PASS" && <span className={styles.badge}>{t("pass.recommended")}</span>}
                      <h3>{PRODUCT_NAMES[o.product]}</h3>
                      <p className={styles.price} aria-busy={prices === null}>
                        {prices === null ? <span className={styles.skeleton} /> : (prices[o.priceId] ?? listPrice(o.listPriceUsdCents))}
                        {o.product === "HOST_PASS" && prices !== null && <span className={styles.per}>{t("pass.perYear")}</span>}
                      </p>
                      <p className={styles.blurb}>{t(BLURBS[o.product])}</p>
                      <Button
                        block
                        disabled={!adult || step.kind === "paying"}
                        loading={step.kind === "paying" && step.product === o.product}
                        loadingLabel={t("pass.opening")}
                        onClick={() => buy(o.product)}
                      >
                        {t("pass.get", { product: PRODUCT_NAMES[o.product] })}
                      </Button>
                    </div>
                  ))}
                </div>
                <label className={styles.adult}>
                  <input type="checkbox" checked={adult} onChange={(e) => setAdult(e.target.checked)} />
                  {t("pass.adult")}
                </label>
                {step.kind === "choose" && step.error && (
                  <p className={styles.error} role="alert">
                    {t("pass.declined")}
                  </p>
                )}
                <p className={styles.small}>{t("pass.small")}</p>
              </>
            )}
            <Button
              variant="quiet"
              onClick={() => {
                paddle.current?.closeCheckout();
                onDismiss();
              }}
            >
              {t("common.notNow")}
            </Button>
          </>
        )}
      </div>
    </div>
  );
}

function Activation({ step, party, support }: { step: Extract<Step, { kind: "activating" | "active" }>; party: boolean; support: string }) {
  const { t, lang } = useI18n();
  if (step.kind === "active") {
    return (
      <div className={styles.center} role="status">
        <p className={styles.done} aria-hidden="true">
          🎉
        </p>
        <h2 id="pass-title" className={styles.title}>
          {t("pass.done", { product: PRODUCT_NAMES[step.pass.type], until: formatPassEnd(step.pass.endsAt, new Date(), lang) })}
        </h2>
        {party && <p className={styles.subtitle}>{t("pass.startsSoon")}</p>}
      </div>
    );
  }
  return (
    <div className={styles.center} role="status">
      {!step.gaveUp && <Spinner label={t("pass.activating")} size={40} />}
      <h2 id="pass-title" className={styles.title}>
        {t("pass.activatingTitle")}
      </h2>
      {step.slow && (
        <p className={styles.subtitle}>{step.gaveUp ? t("pass.gaveUp") : party ? t("pass.slowParty") : t("pass.slowAccount")}</p>
      )}
      {step.slow && (
        <a className={styles.link} href={`mailto:${support}`}>
          {t("pass.support")}
        </a>
      )}
    </div>
  );
}
