import { useEffect, useState } from "react";
import { Link, Navigate, useNavigate } from "react-router";
import { Button } from "../../components/Button";
import { HostLayout } from "../../components/HostLayout";
import { Notice } from "../../components/Notice";
import { api, isApiError } from "../../lib/api";
import { fetchAccess, formatPassEnd, type AccessStatus } from "../../lib/billing";
import { useAuth } from "../../lib/auth";
import { LENGTH_ORDER, TONE_ORDER } from "../../lib/game/labels";
import { loadPref, saveSeat, savePref } from "../../lib/game/storage";
import type { GameLength, RoomMode, Tone } from "../../lib/game/types";
import { unlockAudio } from "../../lib/game/useHostVoice";
import { LANGUAGES, isLang, makeI18n, useI18n, type I18n, type Lang } from "../../lib/i18n";
import styles from "./NewPartyPage.module.css";

interface Choice {
  tone: Tone;
  length: GameLength;
  mode: RoomMode;
  hideCode: boolean;
  language: Lang;
}

function lastChoice(uiLanguage: Lang): Choice {
  try {
    const saved = JSON.parse(loadPref("newParty") ?? "{}") as Partial<Choice>;
    return {
      tone: saved.tone && TONE_ORDER.includes(saved.tone) ? saved.tone : "CHEEKY",
      length: saved.length === "LONG" ? "LONG" : "SHORT",
      mode: saved.mode === "STREAMER" ? "STREAMER" : "STANDARD",
      hideCode: saved.hideCode === true,
      language: isLang(saved.language) ? saved.language : uiLanguage,
    };
  } catch {
    return { tone: "CHEEKY", length: "SHORT", mode: "STANDARD", hideCode: false, language: uiLanguage };
  }
}

export function accessLine(status: AccessStatus["access"], i18n: I18n = makeI18n("en")): string {
  const { t, lang } = i18n;
  const hostPass = status.passes.find((p) => p.type === "HOST_PASS");
  switch (status.nextGame) {
    case "PARTY_PASS":
      return t("access.partyUntil", { until: formatPassEnd(status.passes.find((p) => p.type === "PARTY_PASS")!.endsAt, new Date(), lang) });
    case "HOST_PASS":
      return t("access.hostLeft", { left: hostPass?.gamesLeftThisMonth ?? t("access.unlimited") });
    case "FREE":
      return t("access.free");
    default:
      return status.paywallReason === "PAYWALL_MONTHLY_LIMIT" ? t("access.monthly") : t("access.freeUsed");
  }
}

export type { AccessStatus };

/** H3: set up a party in 10 seconds. The create click also unlocks audio for the host's voice. */
export function NewPartyPage() {
  const { me, loading } = useAuth();
  const i18n = useI18n();
  const { t } = i18n;
  const navigate = useNavigate();
  const [choice, setChoice] = useState<Choice>(() => lastChoice(i18n.lang));
  const [status, setStatus] = useState<AccessStatus | null>(null);
  const [confirmSpicy, setConfirmSpicy] = useState(false);
  const [adultsConfirmed, setAdultsConfirmed] = useState(false);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!me) return;
    const ctl = new AbortController();
    fetchAccess(ctl.signal).then(setStatus, () => undefined);
    return () => ctl.abort();
  }, [me]);

  if (loading)
    return (
      <HostLayout narrow>
        <p className={styles.skeleton} aria-busy="true">
          {t("common.loadingDots")}
        </p>
      </HostLayout>
    );
  if (!me) return <Navigate to={`/login?returnTo=${encodeURIComponent("/new")}`} replace />;

  const pickTone = (tone: Tone) => {
    if (tone === "SPICY" && !adultsConfirmed) {
      setConfirmSpicy(true);
      return;
    }
    setChoice((c) => ({ ...c, tone }));
  };

  const create = async () => {
    unlockAudio();
    setCreating(true);
    setError(null);
    savePref("newParty", JSON.stringify(choice));
    try {
      const room = await api<{ code: string; screenToken: string; joinUrl: string }>("/api/rooms", {
        method: "POST",
        body: { ...choice, adultsConfirmed: choice.tone === "SPICY" ? adultsConfirmed : undefined },
      });
      saveSeat(room.code, { token: room.screenToken, kind: "owner" });
      void navigate(`/screen/${room.code}`);
    } catch (e) {
      setCreating(false);
      if (isApiError(e) && e.status === 401) {
        void navigate(`/login?returnTo=${encodeURIComponent("/new")}`);
        return;
      }
      setError(isApiError(e, "DRAIN_MODE") ? i18n.error("DRAIN_MODE") : t("new.createFailed"));
    }
  };

  const drain = status?.drainMode === true;

  return (
    <HostLayout>
      <div className={styles.page}>
        <h1 className={styles.title}>{t("new.title")}</h1>
        {drain && (
          <Notice tone="error" title={t("new.drainTitle")}>
            {i18n.error("DRAIN_MODE")}
          </Notice>
        )}

        <fieldset className={styles.group}>
          <legend>{t("new.tone")}</legend>
          <div className={styles.cards}>
            {TONE_ORDER.map((tone) => (
              <label key={tone} className={styles.card} data-selected={choice.tone === tone || undefined}>
                <input type="radio" name="tone" value={tone} checked={choice.tone === tone} onChange={() => pickTone(tone)} />
                <span className={styles.cardTitle}>{t(`tone.${tone}.title`)}</span>
                <span className={styles.cardSample}>{t(`tone.${tone}.sample`)}</span>
              </label>
            ))}
          </div>
        </fieldset>

        <fieldset className={styles.group}>
          <legend>{t("new.length")}</legend>
          <div className={styles.cards}>
            {LENGTH_ORDER.map((length) => (
              <label key={length} className={styles.card} data-selected={choice.length === length || undefined}>
                <input
                  type="radio"
                  name="length"
                  value={length}
                  checked={choice.length === length}
                  onChange={() => setChoice((c) => ({ ...c, length }))}
                />
                <span className={styles.cardTitle}>{t(`length.${length}.title`)}</span>
                <span className={styles.cardSample}>{t(`length.${length}.detail`)}</span>
              </label>
            ))}
          </div>
        </fieldset>

        <fieldset className={styles.group}>
          <legend>{t("new.mode")}</legend>
          <div className={styles.cards}>
            <label className={styles.card} data-selected={choice.mode === "STANDARD" || undefined}>
              <input
                type="radio"
                name="mode"
                value="STANDARD"
                checked={choice.mode === "STANDARD"}
                onChange={() => setChoice((c) => ({ ...c, mode: "STANDARD", hideCode: false }))}
              />
              <span className={styles.cardTitle}>{t("mode.STANDARD.title")}</span>
              <span className={styles.cardSample}>{t("mode.STANDARD.detail")}</span>
            </label>
            <label className={styles.card} data-selected={choice.mode === "STREAMER" || undefined}>
              <input
                type="radio"
                name="mode"
                value="STREAMER"
                checked={choice.mode === "STREAMER"}
                onChange={() => setChoice((c) => ({ ...c, mode: "STREAMER" }))}
              />
              <span className={styles.cardTitle}>{t("mode.STREAMER.title")}</span>
              <span className={styles.cardSample}>{t("mode.STREAMER.detail")}</span>
            </label>
          </div>
          {choice.mode === "STREAMER" && (
            <label className={styles.toggle}>
              <input type="checkbox" checked={choice.hideCode} onChange={(e) => setChoice((c) => ({ ...c, hideCode: e.target.checked }))} />
              {t("new.hideCode")}
            </label>
          )}
        </fieldset>

        <fieldset className={styles.group}>
          <legend>{t("new.language")}</legend>
          <p className={styles.groupHint}>{t("new.languageHint")}</p>
          <div className={styles.cards}>
            {LANGUAGES.map((l) => (
              <label key={l.code} className={styles.card} data-selected={choice.language === l.code || undefined}>
                <input
                  type="radio"
                  name="language"
                  value={l.code}
                  checked={choice.language === l.code}
                  onChange={() => setChoice((c) => ({ ...c, language: l.code }))}
                />
                <span className={styles.cardTitle} lang={l.code}>
                  {l.name}
                </span>
              </label>
            ))}
          </div>
        </fieldset>

        <p className={styles.access} aria-live="polite" aria-busy={!status}>
          {status ? accessLine(status.access, i18n) : <span className={styles.skeletonLine} />}
        </p>

        <p className={styles.narrowHint}>
          {t("new.narrowHint")} <span>{t("new.narrowHint2")}</span>
        </p>

        {error && <Notice tone="error">{error}</Notice>}
        <div className={styles.actions}>
          <Button big onClick={() => void create()} loading={creating} loadingLabel={t("new.opening")} disabled={drain}>
            {t("new.create")}
          </Button>
          <Link to="/account" className={styles.secondary}>
            {t("layout.myAccount")}
          </Link>
        </div>
      </div>

      {confirmSpicy && (
        <div className={styles.backdrop} role="presentation">
          <div className={styles.dialog} role="dialog" aria-modal="true" aria-labelledby="spicy-title">
            <h2 id="spicy-title">{t("new.spicyTitle")}</h2>
            <p>{t("new.spicyText")}</p>
            <div className={styles.dialogActions}>
              <Button
                onClick={() => {
                  setAdultsConfirmed(true);
                  setChoice((c) => ({ ...c, tone: "SPICY" }));
                  setConfirmSpicy(false);
                }}
              >
                {t("common.yes")}
              </Button>
              <Button
                variant="secondary"
                onClick={() => {
                  setChoice((c) => ({ ...c, tone: "CHEEKY" }));
                  setConfirmSpicy(false);
                }}
              >
                {t("new.chooseCheeky")}
              </Button>
            </div>
          </div>
        </div>
      )}
    </HostLayout>
  );
}
