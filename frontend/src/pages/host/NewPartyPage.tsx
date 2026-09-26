import { useEffect, useState } from "react";
import { Link, Navigate, useNavigate } from "react-router";
import { Button } from "../../components/Button";
import { HostLayout } from "../../components/HostLayout";
import { Notice } from "../../components/Notice";
import { api, isApiError } from "../../lib/api";
import { fetchAccess, formatPassEnd, type AccessStatus } from "../../lib/billing";
import { useAuth } from "../../lib/auth";
import { CLEAN_COMPANIES, COMPANY_ORDER, LENGTH_ORDER, MAX_CONTEXT_CHARS, TONE_ORDER } from "../../lib/game/labels";
import { loadPref, saveSeat, savePref } from "../../lib/game/storage";
import type { Company, GameLength, RoomMode, Tone } from "../../lib/game/types";
import { unlockAudio } from "../../lib/game/useHostVoice";
import { LANGUAGES, isLang, makeI18n, useI18n, type I18n, type Lang } from "../../lib/i18n";
import styles from "./NewPartyPage.module.css";

interface Choice {
  company: Company;
  context: string;
  tone: Tone;
  length: GameLength;
  mode: RoomMode;
  hideCode: boolean;
  language: Lang;
}

function lastChoice(uiLanguage: Lang): Choice {
  try {
    const saved = JSON.parse(loadPref("newParty") ?? "{}") as Partial<Choice>;
    const company = saved.company && COMPANY_ORDER.includes(saved.company) ? saved.company : "FRIENDS";
    const tone = saved.tone && TONE_ORDER.includes(saved.tone) ? saved.tone : "CHEEKY";
    return {
      company,
      context: typeof saved.context === "string" ? saved.context.slice(0, MAX_CONTEXT_CHARS) : "",
      tone: tone === "SPICY" && CLEAN_COMPANIES.has(company) ? "CHEEKY" : tone,
      length: saved.length === "LONG" ? "LONG" : "SHORT",
      mode: saved.mode === "STREAMER" ? "STREAMER" : "STANDARD",
      hideCode: saved.hideCode === true,
      language: isLang(saved.language) ? saved.language : uiLanguage,
    };
  } catch {
    return { company: "FRIENDS", context: "", tone: "CHEEKY", length: "SHORT", mode: "STANDARD", hideCode: false, language: uiLanguage };
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
  /** The 18+ question was asked by "Create room" itself: a "Yes" goes on to create the room. */
  const [createAfterConfirm, setCreateAfterConfirm] = useState(false);
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

  const pickCompany = (company: Company) =>
    setChoice((c) => ({ ...c, company, tone: c.tone === "SPICY" && CLEAN_COMPANIES.has(company) ? "CHEEKY" : c.tone }));

  const pickTone = (tone: Tone) => {
    if (tone === "SPICY" && !adultsConfirmed) {
      setConfirmSpicy(true);
      return;
    }
    setChoice((c) => ({ ...c, tone }));
  };

  const create = async (adults = adultsConfirmed) => {
    // Spicy restored from last time was never confirmed for tonight's party: ask first (the server insists too).
    if (choice.tone === "SPICY" && !adults) {
      setCreateAfterConfirm(true);
      setConfirmSpicy(true);
      return;
    }
    unlockAudio();
    setCreating(true);
    setError(null);
    savePref("newParty", JSON.stringify(choice));
    try {
      const room = await api<{ code: string; screenToken: string; joinUrl: string }>("/api/rooms", {
        method: "POST",
        body: {
          ...choice,
          context: choice.context.trim() || undefined,
          adultsConfirmed: choice.tone === "SPICY" ? adults : undefined,
        },
      });
      saveSeat(room.code, { token: room.screenToken, kind: "owner" });
      void navigate(`/screen/${room.code}`);
    } catch (e) {
      setCreating(false);
      if (isApiError(e) && e.status === 401) {
        void navigate(`/login?returnTo=${encodeURIComponent("/new")}`);
        return;
      }
      setError(
        isApiError(e, "DRAIN_MODE") || isApiError(e, "RATE_LIMITED")
          ? i18n.error(e.code)
          : isApiError(e, "VALIDATION_FAILED") && e.details.fields?.context
            ? t("new.contextInvalid")
            : t("new.createFailed"),
      );
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
          <legend>{t("new.company")}</legend>
          <p className={styles.groupHint}>{t("new.companyHint")}</p>
          <div className={styles.cards}>
            {COMPANY_ORDER.map((company) => (
              <label key={company} className={styles.card} data-selected={choice.company === company || undefined}>
                <input
                  type="radio"
                  name="company"
                  value={company}
                  checked={choice.company === company}
                  onChange={() => pickCompany(company)}
                />
                <span className={styles.cardTitle}>{t(`company.${company}.title`)}</span>
                <span className={styles.cardSample}>{t(`company.${company}.detail`)}</span>
              </label>
            ))}
          </div>
          <label className={styles.context}>
            <span>{t("new.context")}</span>
            <input
              type="text"
              value={choice.context}
              maxLength={MAX_CONTEXT_CHARS}
              placeholder={t("new.contextPlaceholder")}
              onChange={(e) => setChoice((c) => ({ ...c, context: e.target.value }))}
            />
          </label>
        </fieldset>

        <fieldset className={styles.group}>
          <legend>{t("new.tone")}</legend>
          <div className={styles.cards}>
            {TONE_ORDER.map((tone) => (
              <label
                key={tone}
                className={styles.card}
                data-selected={choice.tone === tone || undefined}
                data-disabled={(tone === "SPICY" && CLEAN_COMPANIES.has(choice.company)) || undefined}
              >
                <input
                  type="radio"
                  name="tone"
                  value={tone}
                  checked={choice.tone === tone}
                  disabled={tone === "SPICY" && CLEAN_COMPANIES.has(choice.company)}
                  onChange={() => pickTone(tone)}
                />
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
                  if (createAfterConfirm) {
                    setCreateAfterConfirm(false);
                    void create(true);
                  }
                }}
              >
                {t("common.yes")}
              </Button>
              <Button
                variant="secondary"
                onClick={() => {
                  setChoice((c) => ({ ...c, tone: "CHEEKY" }));
                  setConfirmSpicy(false);
                  setCreateAfterConfirm(false);
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
