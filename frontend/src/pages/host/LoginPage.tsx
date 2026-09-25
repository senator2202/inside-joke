import { useI18n } from "../../lib/i18n";
import { useEffect, useRef, useState, type FormEvent } from "react";
import { useNavigate, useSearchParams } from "react-router";
import { Button } from "../../components/Button";
import { CodeInput } from "../../components/CodeInput";
import { Field } from "../../components/Field";
import { HostLayout } from "../../components/HostLayout";
import { Notice } from "../../components/Notice";
import { api, isApiError } from "../../lib/api";
import { useAuth } from "../../lib/auth";
import { isValidEmail } from "../../lib/email";
import { consumeReturnTo, rememberReturnTo, safeReturnTo } from "../../lib/returnTo";
import { useCountdown } from "../../lib/useCountdown";
import styles from "./LoginPage.module.css";

interface AuthConfig {
  googleEnabled: boolean;
}

interface SendResult {
  sent: boolean;
  resendAfterSeconds: number;
}

type Step = "email" | "code";

/** H2 (sign in) and H2b (code from the email) share one route so "Change email" keeps what was typed. */
/** The sign-in options are asked for up to three times, 0.3 s and 0.6 s apart, before the page says it couldn't. */
const CONFIG_ATTEMPTS = 3;
const CONFIG_RETRY_MS = 300;

export function LoginPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { me, loading, refresh } = useAuth();
  const { t, lang } = useI18n();
  const returnTo = safeReturnTo(params.get("returnTo"));

  const [config, setConfig] = useState<AuthConfig | null>(null);
  const [configFailed, setConfigFailed] = useState(false);
  const [step, setStep] = useState<Step>("email");
  const [email, setEmail] = useState("");
  const [emailError, setEmailError] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(params.get("error") === "google" ? "google" : null);
  const [busy, setBusy] = useState<"google" | "send" | "verify" | "resend" | null>(null);

  const [code, setCode] = useState("");
  const [codeError, setCodeError] = useState<string | null>(null);
  const [locked, setLocked] = useState(false);
  const [resendAt, setResendAt] = useState<number | null>(null);
  const resendIn = useCountdown(resendAt);
  const redirected = useRef(false);

  useEffect(() => {
    const controller = new AbortController();
    let attempt = 0;
    let retry: ReturnType<typeof setTimeout> | undefined;
    const load = () => {
      api<AuthConfig>("/api/auth/config", { signal: controller.signal })
        .then(setConfig)
        .catch((e: unknown) => {
          if (controller.signal.aborted) return; // the page was left: that is not an answer
          attempt += 1;
          if (attempt < CONFIG_ATTEMPTS) {
            retry = setTimeout(load, attempt * CONFIG_RETRY_MS);
            return;
          }
          console.warn("Sign-in options could not be loaded from /api/auth/config", e);
          setConfig({ googleEnabled: false });
          setConfigFailed(true);
        });
    };
    load();
    return () => {
      controller.abort();
      clearTimeout(retry);
    };
  }, []);

  useEffect(() => {
    if (!loading && me && !redirected.current) {
      redirected.current = true;
      void navigate(returnTo, { replace: true });
    }
  }, [loading, me, navigate, returnTo]);

  const continueWithGoogle = () => {
    setBusy("google");
    rememberReturnTo(returnTo);
    window.location.assign("/oauth2/authorization/google");
  };

  const sendCode = async (mode: "send" | "resend") => {
    setBusy(mode);
    setFormError(null);
    try {
      const result = await api<SendResult>("/api/auth/magic-link", { method: "POST", body: { email: email.trim(), language: lang } });
      rememberReturnTo(returnTo);
      setResendAt(Date.now() + result.resendAfterSeconds * 1000);
      setCode("");
      setCodeError(null);
      setLocked(false);
      setStep("code");
    } catch (e) {
      if (isApiError(e, "VALIDATION_FAILED")) {
        setEmailError(t("login.emailInvalid"));
        setStep("email");
      } else if (isApiError(e, "RATE_LIMITED")) {
        setFormError("rate");
      } else if (isApiError(e, "EMAIL_SEND_FAILED")) {
        setFormError("send");
      } else {
        setFormError("network");
      }
    } finally {
      setBusy(null);
    }
  };

  const onSubmitEmail = (e: FormEvent) => {
    e.preventDefault();
    if (!isValidEmail(email)) {
      setEmailError(t("login.emailInvalid"));
      return;
    }
    setEmailError(null);
    void sendCode("send");
  };

  const verify = async (value: string) => {
    if (busy === "verify" || locked) return;
    setBusy("verify");
    setCodeError(null);
    try {
      await api("/api/auth/email-code/verify", { method: "POST", body: { email: email.trim(), code: value } });
      redirected.current = true;
      await refresh();
      consumeReturnTo();
      void navigate(returnTo, { replace: true });
    } catch (e) {
      setCode("");
      if (isApiError(e, "CODE_INVALID")) {
        const left = Number(e.details.attemptsLeft ?? 0);
        setCodeError(t("login.codeWrong", { left }));
      } else if (isApiError(e, "CODE_LOCKED")) {
        setLocked(true);
        setCodeError(t("login.codeLocked"));
      } else if (isApiError(e, "CODE_EXPIRED")) {
        setLocked(true);
        setCodeError(t("login.codeExpired"));
      } else if (isApiError(e, "RATE_LIMITED")) {
        setCodeError(t("login.codeRate"));
      } else {
        setCodeError(t("login.codeNetwork"));
      }
    } finally {
      setBusy(null);
    }
  };

  const changeEmail = () => {
    setStep("email");
    setCode("");
    setCodeError(null);
    setLocked(false);
  };

  return (
    <HostLayout narrow>
      {step === "email" ? (
        <section className={styles.panel} aria-labelledby="login-title">
          <h1 id="login-title">{t("login.title")}</h1>
          <p className={styles.lede}>{t("login.lede")}</p>

          {formError === "google" && <Notice tone="error">{t("login.err.google")}</Notice>}
          {formError === "rate" && <Notice tone="error">{t("login.err.rate")}</Notice>}
          {formError === "send" && <Notice tone="error">{t("login.err.send")}</Notice>}
          {formError === "network" && <Notice tone="error">{t("login.err.network")}</Notice>}
          {configFailed && <Notice tone="error">{t("login.err.config")}</Notice>}

          {config?.googleEnabled && (
            <>
              <Button
                block
                big
                onClick={continueWithGoogle}
                loading={busy === "google"}
                disabled={busy !== null}
                loadingLabel={t("login.openingGoogle")}
              >
                <GoogleMark /> {t("login.google")}
              </Button>
              <div className={styles.divider} role="separator" aria-label={t("login.or")}>
                <span>{t("login.orEmail")}</span>
              </div>
            </>
          )}

          <form className={styles.form} onSubmit={onSubmitEmail} noValidate>
            <Field
              label={t("login.email")}
              type="email"
              name="email"
              autoComplete="email"
              inputMode="email"
              placeholder="name@example.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              error={emailError}
              disabled={busy !== null}
              maxLength={254}
            />
            <Button
              type="submit"
              block
              variant={config?.googleEnabled ? "secondary" : "primary"}
              loading={busy === "send"}
              disabled={busy !== null}
              loadingLabel={t("login.sending")}
            >
              {t("login.getCode")}
            </Button>
          </form>

          <p className={styles.legal}>
            {t("login.legalBefore")}
            <a href="/terms.html">{t("login.terms")}</a>
            {t("login.legalAnd")}
            <a href="/privacy.html">{t("login.privacy")}</a>
            {t("login.legalAfter")}
          </p>
        </section>
      ) : (
        <section className={styles.panel} aria-labelledby="code-title">
          <h1 id="code-title">{t("login.checkTitle")}</h1>
          <p className={styles.lede}>
            {t("login.sentBefore")}
            <strong className={styles.email}>{email.trim()}</strong>
            {t("login.sentAfter")}
          </p>

          <div className={styles.codeRow}>
            <CodeInput
              value={code}
              onChange={setCode}
              onComplete={(value) => void verify(value)}
              disabled={busy === "verify" || locked}
              invalid={codeError !== null}
              describedBy={codeError ? "code-error" : "code-hint"}
            />
            {busy === "verify" && (
              <p className={styles.checking} role="status">
                {t("common.checking")}
              </p>
            )}
          </div>

          {codeError ? (
            <p id="code-error" className={styles.codeError} role="alert">
              {codeError}
            </p>
          ) : (
            <p id="code-hint" className={styles.hint}>
              {t("login.hint")}
            </p>
          )}

          {formError === "rate" && <Notice tone="error">{t("login.err.rate")}</Notice>}
          {formError === "send" && <Notice tone="error">{t("login.err.send")}</Notice>}
          {formError === "network" && <Notice tone="error">{t("login.err.network")}</Notice>}

          <div className={styles.actions}>
            <Button
              variant={locked ? "primary" : "secondary"}
              onClick={() => void sendCode("resend")}
              disabled={resendIn > 0 || busy !== null}
              loading={busy === "resend"}
              loadingLabel={t("login.resending")}
            >
              {resendIn > 0 ? t("login.resendIn", { s: resendIn }) : t("login.resend")}
            </Button>
            <Button variant="quiet" onClick={changeEmail} disabled={busy !== null}>
              {t("login.changeEmail")}
            </Button>
          </div>
        </section>
      )}
    </HostLayout>
  );
}

function GoogleMark() {
  return (
    <svg width="22" height="22" viewBox="0 0 48 48" aria-hidden="true">
      <path
        fill="#FFC107"
        d="M43.6 20.1H42V20H24v8h11.3C33.7 32.7 29.2 36 24 36c-6.6 0-12-5.4-12-12s5.4-12 12-12c3.1 0 5.8 1.2 7.9 3.1l5.7-5.7C34 6.1 29.3 4 24 4 13 4 4 13 4 24s9 20 20 20 20-9 20-20c0-1.3-.1-2.6-.4-3.9z"
      />
      <path
        fill="#FF3D00"
        d="m6.3 14.7 6.6 4.8C14.7 15.1 19 12 24 12c3.1 0 5.8 1.2 7.9 3.1l5.7-5.7C34 6.1 29.3 4 24 4 16.3 4 9.7 8.3 6.3 14.7z"
      />
      <path
        fill="#4CAF50"
        d="M24 44c5.2 0 9.9-2 13.4-5.2l-6.2-5.2C29.2 35.1 26.7 36 24 36c-5.2 0-9.6-3.3-11.3-8l-6.5 5C9.5 39.6 16.2 44 24 44z"
      />
      <path fill="#1976D2" d="M43.6 20.1H42V20H24v8h11.3c-.8 2.2-2.2 4.2-4.1 5.6l6.2 5.2C37 39.2 44 34 44 24c0-1.3-.1-2.6-.4-3.9z" />
    </svg>
  );
}
