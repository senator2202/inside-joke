import { useI18n } from "../../lib/i18n";
import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router";
import { Button, ButtonLink } from "../../components/Button";
import { HostLayout } from "../../components/HostLayout";
import { Spinner } from "../../components/Spinner";
import { api, isApiError } from "../../lib/api";
import { useAuth } from "../../lib/auth";
import { consumeReturnTo } from "../../lib/returnTo";
import styles from "./AuthCallbackPage.module.css";

type State = { kind: "working" } | { kind: "elsewhere"; path: string } | { kind: "invalid" } | { kind: "failed" };

/**
 * Tokens are single-use, and React may run effects twice in development, so each token is verified
 * exactly once per page load and every caller shares the same promise.
 */
const verifications = new Map<string, Promise<unknown>>();

function verifyOnce(token: string): Promise<unknown> {
  let pending = verifications.get(token);
  if (!pending) {
    pending = api("/api/auth/magic-link/verify", { method: "POST", body: { token } });
    verifications.set(token, pending);
  }
  return pending;
}

/** H2c: landing spot for the email link (?token=) and for a finished Google sign-in (?provider=google). */
export function AuthCallbackPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { refresh } = useAuth();
  const { t } = useI18n();
  const [token] = useState(() => params.get("token"));
  const [provider] = useState(() => params.get("provider"));
  const [state, setState] = useState<State>({ kind: "working" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    if (token && window.location.search.includes("token=")) {
      // Keep the one-time token out of the address bar, history and any screenshots.
      void navigate("/auth/callback", { replace: true });
    }

    const finish = async () => {
      if (token) {
        await verifyOnce(token);
      } else if (provider !== "google") {
        throw new Error("missing token");
      }
      await refresh();
      if (cancelled) return;
      const { path, startedHere } = consumeReturnTo();
      if (token && !startedHere) {
        setState({ kind: "elsewhere", path });
      } else {
        void navigate(path, { replace: true });
      }
    };

    finish().catch((e: unknown) => {
      if (cancelled) return;
      if (isApiError(e) && e.status !== 0 && e.status < 500) setState({ kind: "invalid" });
      else if (e instanceof Error && e.message === "missing token") setState({ kind: "invalid" });
      else {
        // A network failure didn't consume the token: allow a fresh attempt.
        if (token) verifications.delete(token);
        setState({ kind: "failed" });
      }
    });
    return () => {
      cancelled = true;
    };
  }, [token, provider, navigate, refresh, attempt]);

  return (
    <HostLayout narrow>
      <section className={styles.panel} aria-live="polite">
        {state.kind === "working" && (
          <div className={styles.working}>
            <Spinner label={t("callback.working")} size={32} />
            <h1>{t("callback.workingTitle")}</h1>
          </div>
        )}

        {state.kind === "elsewhere" && (
          <>
            <h1>{t("callback.elsewhereTitle")}</h1>
            <p className={styles.lede}>{t("callback.elsewhereText")}</p>
            <ButtonLink to={state.path} replace block>
              {t("callback.continue")}
            </ButtonLink>
          </>
        )}

        {state.kind === "invalid" && (
          <>
            <h1>{t("callback.invalidTitle")}</h1>
            <p className={styles.lede}>{t("callback.invalidText")}</p>
            <ButtonLink to="/login" replace block>
              {t("callback.newCode")}
            </ButtonLink>
          </>
        )}

        {state.kind === "failed" && (
          <>
            <h1>{t("callback.failedTitle")}</h1>
            <p className={styles.lede}>{t("callback.failedText")}</p>
            <Button
              block
              onClick={() => {
                setState({ kind: "working" });
                setAttempt((n) => n + 1);
              }}
            >
              {t("common.tryAgain")}
            </Button>
          </>
        )}
      </section>
    </HostLayout>
  );
}
