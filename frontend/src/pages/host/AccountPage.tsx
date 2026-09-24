import { useCallback, useEffect, useState } from "react";
import { Navigate, useNavigate } from "react-router";
import { PassPicker } from "../../components/billing/PassPicker";
import { Button, ButtonLink } from "../../components/Button";
import { HostLayout } from "../../components/HostLayout";
import { Notice } from "../../components/Notice";
import { api } from "../../lib/api";
import { useAuth } from "../../lib/auth";
import { PRODUCT_NAMES, fetchAccess, formatPassEnd, type AccessStatus, type Pass } from "../../lib/billing";
import { makeI18n, useI18n, type I18n } from "../../lib/i18n";
import styles from "./AccountPage.module.css";

export function freeGameLine(access: AccessStatus["access"], now: Date = new Date(), i18n: I18n = makeI18n("en")): string {
  if (!access.freeGamesEnabled) return i18n.t("account.freePaused");
  if (access.freeGameAvailable || !access.nextFreeGameAt) return i18n.t("account.freeAvailable");
  const days = Math.max(1, Math.ceil((new Date(access.nextFreeGameAt).getTime() - now.getTime()) / 86_400_000));
  return i18n.tp("account.nextFree", days);
}

function passDetail(p: Pass, { t, lang }: I18n): string {
  const until = t("account.activeUntil", { until: formatPassEnd(p.endsAt, new Date(), lang) });
  if (p.type === "HOST_PASS" && p.monthlyGameLimit !== null) {
    return `${until} · ${t("account.gamesLeft", { left: p.gamesLeftThisMonth ?? 0, limit: p.monthlyGameLimit })}`;
  }
  return `${until} · ${t("account.unlimited")}`;
}

/** H6: access status and account management. */
export function AccountPage() {
  const { me, loading, logout, refresh } = useAuth();
  const i18n = useI18n();
  const { t } = i18n;
  const navigate = useNavigate();
  const [status, setStatus] = useState<AccessStatus | null>(null);
  const [failed, setFailed] = useState(false);
  const [picker, setPicker] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState(false);
  const [leaving, setLeaving] = useState(false);

  const load = useCallback((signal?: AbortSignal) => {
    fetchAccess(signal).then(
      (s) => {
        setStatus(s);
        setFailed(false);
      },
      (e: unknown) => {
        if (!(e instanceof DOMException && e.name === "AbortError")) setFailed(true);
      },
    );
  }, []);

  useEffect(() => {
    if (!me) return;
    const ctl = new AbortController();
    load(ctl.signal);
    return () => ctl.abort();
  }, [me, load]);

  if (loading)
    return (
      <HostLayout narrow>
        <div className={styles.skeleton} aria-busy="true" aria-label={t("common.loading")} />
      </HostLayout>
    );
  if (!me) return leaving ? null : <Navigate to={`/login?returnTo=${encodeURIComponent("/account")}`} replace />;

  // Once the session is gone this page would redirect to sign-in; `leaving` sends the host to the landing page instead.
  const signOut = async () => {
    setLeaving(true);
    void navigate("/");
    await logout().catch(() => undefined);
  };

  const deleteAccount = async () => {
    setDeleting(true);
    setDeleteError(false);
    try {
      await api<void>("/api/me", { method: "DELETE" });
      setLeaving(true);
      void navigate("/?deleted=1", { replace: true });
      await refresh();
    } catch {
      setDeleting(false);
      setDeleteError(true);
    }
  };

  return (
    <HostLayout>
      <div className={styles.page}>
        <h1 className={styles.title}>{t("account.title")}</h1>

        <section className={styles.card} aria-labelledby="acc-signin">
          <h2 id="acc-signin">{t("account.signIn")}</h2>
          <p className={styles.big}>{me.email}</p>
          <p>{me.googleLinked ? t("account.google") : t("account.emailCode")}</p>
        </section>

        <section className={styles.card} aria-labelledby="acc-passes" aria-busy={!status && !failed}>
          <h2 id="acc-passes">{t("account.passes")}</h2>
          {failed ? (
            <div className={styles.row}>
              <p>{t("account.couldntLoad")}</p>
              <Button variant="secondary" onClick={() => load()}>
                {t("common.refresh")}
              </Button>
            </div>
          ) : !status ? (
            <>
              <span className={styles.line} />
              <span className={styles.line} />
            </>
          ) : (
            <>
              {status.access.passes.length === 0 ? (
                <p>{t("account.noPasses")}</p>
              ) : (
                <ul className={styles.passes}>
                  {status.access.passes.map((p) => (
                    <li key={p.id}>
                      <strong>{PRODUCT_NAMES[p.type]}</strong>
                      {p.grantedByAdmin && <span className={styles.gift}>{t("account.gift")}</span>}
                      <span>{passDetail(p, i18n)}</span>
                    </li>
                  ))}
                </ul>
              )}
              <p className={styles.free}>{freeGameLine(status.access, new Date(), i18n)}</p>
            </>
          )}
          <p className={styles.small}>{t("account.receipts")}</p>
        </section>

        <div className={styles.actions}>
          <ButtonLink to="/new" big>
            {t("account.createParty")}
          </ButtonLink>
          <Button variant="secondary" big onClick={() => setPicker(true)}>
            {t("account.buyPass")}
          </Button>
        </div>

        <section className={styles.danger} aria-labelledby="acc-manage">
          <h2 id="acc-manage" className="visually-hidden">
            {t("account.manage")}
          </h2>
          <Button variant="quiet" onClick={() => void signOut()}>
            {t("account.signOut")}
          </Button>
          <Button variant="quiet" onClick={() => setConfirmDelete(true)}>
            {t("account.delete")}
          </Button>
        </section>
      </div>

      {picker && (
        <PassPicker
          onDismiss={() => setPicker(false)}
          onActivated={() => {
            setPicker(false);
            load();
          }}
        />
      )}

      {confirmDelete && (
        <div className={styles.backdrop} role="presentation">
          <div className={styles.dialog} role="alertdialog" aria-modal="true" aria-labelledby="del-title" aria-describedby="del-text">
            <h2 id="del-title">{t("account.deleteTitle")}</h2>
            <p id="del-text">{t("account.deleteText")}</p>
            {deleteError && <Notice tone="error">{t("account.deleteFailed")}</Notice>}
            <div className={styles.dialogActions}>
              <Button variant="danger" loading={deleting} loadingLabel={t("account.deleting")} onClick={() => void deleteAccount()}>
                {t("account.deleteForGood")}
              </Button>
              <Button variant="secondary" disabled={deleting} onClick={() => setConfirmDelete(false)}>
                {t("account.keep")}
              </Button>
            </div>
          </div>
        </div>
      )}
    </HostLayout>
  );
}
