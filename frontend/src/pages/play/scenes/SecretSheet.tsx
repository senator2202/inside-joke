import { useState } from "react";
import { Button } from "../../../components/Button";
import { Avatar } from "../../../components/game/Avatar";
import { useI18n } from "../../../lib/i18n";
import styles from "../Play.module.css";
import { type PhoneProps, codeOf } from "./shared";

export function SecretSheet({
  state,
  request,
  onClose,
  onAccepted,
}: Omit<PhoneProps, "offset"> & { onClose: () => void; onAccepted: () => void }) {
  const i18n = useI18n();
  const { t } = i18n;
  const [aboutSomeone, setAboutSomeone] = useState(false);
  const [target, setTarget] = useState<string | null>(null);
  const [text, setText] = useState("");
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const others = (state.players ?? []).filter((p) => p.id !== state.you.playerId);
  const left = state.you.secretsLeft ?? 10;
  const ready = text.trim().length > 0 && (!aboutSomeone || target !== null) && left > 0;

  const submit = async () => {
    setSending(true);
    setError(null);
    try {
      await request("dossier.add", { aboutPlayerId: aboutSomeone ? target : null, text: text.trim() });
      onAccepted();
    } catch (e) {
      const code = codeOf(e);
      setError(
        code === "MODERATION_BLOCKED"
          ? t("phone.topicRefused")
          : code === "MODERATION_UNAVAILABLE"
            ? t("phone.secretUnavailable")
            : i18n.error(code),
      );
    } finally {
      setSending(false);
    }
  };

  return (
    <div className={styles.sheetBackdrop} role="presentation" onClick={onClose}>
      <div className={styles.sheet} role="dialog" aria-modal="true" aria-labelledby="secret-title" onClick={(e) => e.stopPropagation()}>
        <h2 id="secret-title" className={styles.title}>
          {t("phone.secretTitle")}
        </h2>
        <div className={styles.segmented} role="radiogroup" aria-label={t("phone.whoAbout")}>
          <button type="button" role="radio" aria-checked={!aboutSomeone} onClick={() => setAboutSomeone(false)}>
            {t("phone.aboutMe")}
          </button>
          <button type="button" role="radio" aria-checked={aboutSomeone} onClick={() => setAboutSomeone(true)}>
            {t("phone.aboutSomeone")}
          </button>
        </div>
        {aboutSomeone &&
          (others.length === 0 ? (
            <p className={styles.lead}>{t("phone.nobodyYet")}</p>
          ) : (
            <div className={styles.targets} role="radiogroup" aria-label={t("phone.pickPlayer")}>
              {others.map((p) => (
                <button key={p.id} type="button" role="radio" aria-checked={target === p.id} onClick={() => setTarget(p.id)}>
                  <Avatar player={p} size="small" /> {p.name}
                </button>
              ))}
            </div>
          ))}
        <label className="visually-hidden" htmlFor="secret-text">
          {t("phone.secret")}
        </label>
        <textarea
          id="secret-text"
          className={styles.textarea}
          maxLength={200}
          value={text}
          placeholder={t("phone.secretPlaceholder")}
          onChange={(e) => setText(e.target.value)}
        />
        <p className={styles.small}>{t("phone.secretRules")}</p>
        <p className={styles.counter}>{left > 0 ? t("phone.secretsLeft", { left }) : t("phone.secretLimit")}</p>
        {error && (
          <p className={styles.error} role="alert">
            {error}
          </p>
        )}
        <Button big block disabled={!ready} loading={sending} loadingLabel={t("common.checking")} onClick={() => void submit()}>
          {t("phone.giveSecret")}
        </Button>
        <Button variant="quiet" onClick={onClose}>
          {t("common.close")}
        </Button>
      </div>
    </div>
  );
}
