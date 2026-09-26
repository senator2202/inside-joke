import { useEffect, useState } from "react";
import { Button } from "../../../components/Button";
import { Avatar } from "../../../components/game/Avatar";
import { HostLine } from "../../../components/game/HostLine";
import { JoinQr } from "../../../components/game/JoinQr";
import { LENGTH_ORDER, TONE_ORDER } from "../../../lib/game/labels";
import { LANGUAGES, useI18n } from "../../../lib/i18n";
import styles from "../Screen.module.css";
import { type SceneProps } from "./shared";

export function LobbyScene({ state, owner, send }: SceneProps) {
  const { t, tp } = useI18n();
  const players = state.players ?? [];
  const lobby = state.lobby;
  const min = lobby?.minPlayers ?? 3;
  const max = lobby?.maxPlayers ?? 8;
  const [showCode, setShowCode] = useState(false);
  const [editing, setEditing] = useState(false);
  const hidden = state.settings.hideCode && !showCode;

  useEffect(() => {
    if (!showCode) return;
    const t = setTimeout(() => setShowCode(false), 10_000);
    return () => clearTimeout(t);
  }, [showCode]);

  const needed = Math.max(0, min - players.length);
  const joinHost = lobby?.joinUrl ? new URL(lobby.joinUrl).host : window.location.host;

  return (
    <div className={styles.lobby}>
      <section className={styles.joinPanel}>
        {lobby?.joinUrl &&
          (hidden ? (
            <div className={styles.qrHidden} aria-label={t("lobby.qrHidden")}>
              🙈
            </div>
          ) : (
            <JoinQr url={lobby.joinUrl} pulse={players.length === 0} />
          ))}
        <p className={styles.joinHow}>
          {t("lobby.goTo")} <strong>{joinHost}/join</strong>
        </p>
        {hidden ? (
          owner && (
            <Button variant="secondary" onClick={() => setShowCode(true)}>
              {t("lobby.showCode")}
            </Button>
          )
        ) : (
          <p className={styles.code} aria-label={t("lobby.codeAria", { code: state.code ?? "" })}>
            {state.code}
          </p>
        )}
        {owner && state.settings.hideCode && <p className={styles.streamTip}>{t("lobby.streamTip")}</p>}
        {state.settings.mode === "STREAMER" && lobby?.audienceUrl && (
          <p className={styles.audience}>
            {t("lobby.viewersVote")} <strong>{new URL(lobby.audienceUrl).host + new URL(lobby.audienceUrl).pathname}</strong>
            <span className={styles.audienceCount}>👀 {lobby.audienceCount}</span>
          </p>
        )}
      </section>

      <section className={styles.lobbyMain}>
        <h1 className={styles.headline}>
          {players.length === 0
            ? t("lobby.scanToJoin")
            : needed > 0
              ? tp("lobby.needMore", needed)
              : players.length >= max
                ? t("lobby.full")
                : t("lobby.everyoneHere")}
        </h1>
        <ul className={styles.slots}>
          {Array.from({ length: max }, (_, i) => players[i]).map((p, i) => (
            <li key={p?.id ?? `empty-${i}`} className={styles.slot} data-empty={!p || undefined}>
              {p ? (
                <>
                  <Avatar player={p} size="large" />
                  <span className={styles.slotName}>{p.name}</span>
                  {p.bot && <span className={styles.botTag}>{t("lobby.bot")}</span>}
                  {owner && (
                    <button
                      type="button"
                      className={styles.kick}
                      onClick={() => send("player.kick", { playerId: p.id })}
                      aria-label={t("lobby.removeAria", { name: p.name })}
                    >
                      {t("lobby.remove")}
                    </button>
                  )}
                </>
              ) : (
                <span className={styles.slotEmpty} aria-hidden="true">
                  ?
                </span>
              )}
            </li>
          ))}
        </ul>
        {owner && lobby?.botsAllowed && players.length < max && (
          <Button variant="secondary" onClick={() => send("bot.add")}>
            {t("lobby.addBot")}
          </Button>
        )}
        <HostLine text={state.host?.text} />
        <div className={styles.settingsStrip}>
          <span>{t(`tone.${state.settings.tone}.title`)}</span>
          <span>
            {t("lobby.lengthRounds", {
              length: t(`length.${state.settings.length}.title`),
              rounds: state.settings.length === "SHORT" ? 5 : 10,
            })}
          </span>
          <span>{t(`mode.${state.settings.mode}.title`)}</span>
          <span lang={state.settings.language}>{LANGUAGES.find((l) => l.code === state.settings.language)?.name ?? "English"}</span>
          {owner && (
            <button type="button" onClick={() => setEditing((v) => !v)}>
              {editing ? t("common.done") : t("lobby.change")}
            </button>
          )}
        </div>
        {owner && editing && <SettingsEditor state={state} send={send} />}
        {state.secrets ? <p className={styles.secretsCount}>{t("common.secretsCollected", { n: state.secrets })}</p> : null}
      </section>
    </div>
  );
}

function SettingsEditor({ state, send }: Pick<SceneProps, "state" | "send">) {
  const { t } = useI18n();
  const [spicyAsk, setSpicyAsk] = useState(false);
  return (
    <div className={styles.editor}>
      <div role="group" aria-label={t("new.tone")}>
        {TONE_ORDER.map((tone) => (
          <button
            key={tone}
            type="button"
            aria-pressed={state.settings.tone === tone}
            onClick={() => (tone === "SPICY" && state.settings.tone !== "SPICY" ? setSpicyAsk(true) : send("game.settings", { tone }))}
          >
            {t(`tone.${tone}.title`)}
          </button>
        ))}
      </div>
      <div role="group" aria-label={t("new.length")}>
        {LENGTH_ORDER.map((length) => (
          <button
            key={length}
            type="button"
            aria-pressed={state.settings.length === length}
            onClick={() => send("game.settings", { length })}
          >
            {t(`length.${length}.title`)}
          </button>
        ))}
      </div>
      <div role="group" aria-label={t("new.language")}>
        {LANGUAGES.map((l) => (
          <button
            key={l.code}
            type="button"
            lang={l.code}
            aria-pressed={(state.settings.language ?? "en") === l.code}
            onClick={() => send("game.settings", { language: l.code })}
          >
            {l.name}
          </button>
        ))}
      </div>
      {state.settings.mode === "STREAMER" && (
        <label>
          <input
            type="checkbox"
            checked={state.settings.hideCode}
            onChange={(e) => send("game.settings", { hideCode: e.target.checked })}
          />
          {t("lobby.hideCode")}
        </label>
      )}
      {spicyAsk && (
        <div className={styles.inlineConfirm} role="alertdialog" aria-label={t("new.spicyTitle")}>
          <span>{t("new.spicyTitle")}</span>
          <button
            type="button"
            onClick={() => {
              send("game.settings", { tone: "SPICY", adultsConfirmed: true });
              setSpicyAsk(false);
            }}
          >
            {t("common.yes")}
          </button>
          <button type="button" onClick={() => setSpicyAsk(false)}>
            {t("lobby.keepTone", { tone: t(`tone.${state.settings.tone}.title`) })}
          </button>
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- S2 intake
