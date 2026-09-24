import { useCallback, useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { PassPicker } from "../../components/billing/PassPicker";
import { Button, ButtonLink } from "../../components/Button";
import { Countdown } from "../../components/game/Countdown";
import { JoinQr } from "../../components/game/JoinQr";
import { Logo } from "../../components/Logo";
import { Spinner } from "../../components/Spinner";
import { api, isApiError } from "../../lib/api";
import { hostYourOwnUrl } from "../../lib/analytics";
import { forgetSeat, loadSeat, saveSeat } from "../../lib/game/storage";
import type { RoomView } from "../../lib/game/types";
import { useGame } from "../../lib/game/useGame";
import { useHostVoice } from "../../lib/game/useHostVoice";
import { useFollowRoomLanguage, useI18n } from "../../lib/i18n";
import { LanguagePicker } from "../../components/LanguagePicker";
import { GameError } from "../../lib/game/types";
import { CantConnect, RoomGone } from "../system/GameSystemScreens";
import { AnsweringScene, FinaleScene, IntakeScene, LobbyScene, RoundVoteScene, VotingScene, type SceneProps } from "./scenes";
import styles from "./Screen.module.css";

type TokenState = { token: string } | { error: "gone" | "login" | "failed" };

/** Finds this device's screen token: saved seat, or asks the server (owner) / opens a view-only copy (remote). */
function useScreenToken(code: string, remote: boolean): TokenState | null {
  const [result, setResult] = useState<TokenState | null>(() => {
    const seat = loadSeat(code, remote ? "screen" : "owner");
    return seat ? { token: seat.token } : null;
  });
  useEffect(() => {
    if (result) return;
    let cancelled = false;
    const done = (r: TokenState) => !cancelled && setResult(r);
    const request = remote
      ? api<{ screenToken: string }>(`/api/rooms/${code}/screens`, { method: "POST" })
      : api<{ screenToken: string }>(`/api/rooms/${code}/owner-token`);
    request.then(
      (r) => {
        saveSeat(code, { token: r.screenToken, kind: remote ? "screen" : "owner" });
        done({ token: r.screenToken });
      },
      (e: unknown) => {
        if (isApiError(e) && e.status === 401) done({ error: "login" });
        else if (isApiError(e) && (e.status === 404 || e.status === 403)) done({ error: "gone" });
        else done({ error: "failed" });
      },
    );
    return () => {
      cancelled = true;
    };
  }, [code, remote, result]);
  return result;
}

export function ScreenPage({ remote = false }: { remote?: boolean }) {
  const code = (useParams().code ?? "").toUpperCase();
  const navigate = useNavigate();
  const token = useScreenToken(code, remote);
  const { t } = useI18n();

  useEffect(() => {
    if (token && "error" in token && token.error === "login") {
      void navigate(`/login?returnTo=${encodeURIComponent(`/screen/${code}`)}`, { replace: true });
    }
  }, [token, code, navigate]);

  if (!token) return <Loading text={remote ? t("screen.connecting") : t("new.opening")} />;
  if ("error" in token) {
    return token.error === "failed" ? <CantConnect onRetry={() => window.location.reload()} /> : <RoomGone owner={!remote} />;
  }
  return <LiveScreen code={code} token={token.token} remote={remote} />;
}

function Loading({ text }: { text: string }) {
  return (
    <div className={`night ${styles.frame} ${styles.loading}`}>
      <Spinner label={text} size={40} />
      <p>{text}</p>
    </div>
  );
}

function LiveScreen({ code, token, remote }: { code: string; token: string; remote: boolean }) {
  const { state, status, clockOffset, request, retry } = useGame(token);
  const i18n = useI18n();
  const { t } = i18n;
  useFollowRoomLanguage(state?.settings.language);
  const voice = useHostVoice(state?.host?.skipped ? undefined : state?.host?.audioId, !remote);
  const [notice, setNotice] = useState<string | null>(null);
  const owner = !remote && state?.you.role === "OWNER_SCREEN";

  const send = useCallback(
    (type: string, data: unknown = {}) => {
      request(type, data).catch((e: unknown) => {
        const message = e instanceof GameError ? i18n.error(e.code, e.message) : i18n.error(undefined);
        setNotice(message);
        setTimeout(() => setNotice(null), 4000);
      });
    },
    [request, i18n],
  );

  useEffect(() => {
    if (status === "gone") forgetSeat(code, remote ? "screen" : "owner");
  }, [status, code, remote]);

  if (status === "unreachable") return <CantConnect onRetry={retry} />;
  if (status === "gone" && state?.phase !== "CLOSED") return <RoomGone owner={!remote} />;
  if (status === "closed" || state?.phase === "CLOSED") return <PartyOver state={state} owner={!remote} code={code} />;
  if (!state) return <Loading text={remote ? t("screen.connecting") : t("new.opening")} />;

  const props: SceneProps = { state, offset: clockOffset, owner, send, speaking: voice.speaking };
  return (
    <div className={`night ${styles.frame}`}>
      <header className={styles.bar}>
        <Logo to={null} />
        <div className={styles.barRight}>
          {state.code && state.phase !== "LOBBY" && !state.settings.hideCode && <span className={styles.barCode}>{state.code}</span>}
          {(voice.blocked || (remote && !voice.enabled)) && (
            <Button variant="secondary" onClick={voice.enable}>
              {remote ? t("screen.soundOn") : t("screen.voiceOn")}
            </Button>
          )}
          <LanguagePicker tone="night" />
          <Button variant="quiet" onClick={() => void toggleFullscreen()}>
            {t("screen.fullScreen")}
          </Button>
          {owner && <OwnerMenu state={state} send={send} />}
        </div>
      </header>
      <main className={styles.stage}>
        <Scene {...props} />
      </main>
      {notice && (
        <p className={styles.toast} role="alert">
          {notice}
        </p>
      )}
      <Overlays state={state} offset={clockOffset} owner={owner} send={send} />
      {owner && state.paywall && (
        <PassPicker
          reason={state.paywall}
          waitingPlayers={(state.players ?? []).filter((p) => p.connected).length}
          onDismiss={() => send("paywall.dismiss")}
          onActivated={() => send("game.start")}
        />
      )}
      {(status === "reconnecting" || status === "offline") && (
        <div className={styles.overlay} role="alert">
          <div className={styles.overlayCard}>
            {status === "reconnecting" ? (
              <>
                <Spinner label={t("screen.reconnecting")} size={36} />
                <h2>{t("screen.reconnectingTitle")}</h2>
              </>
            ) : (
              <>
                <h2>{t("screen.noConnection")}</h2>
                <Button onClick={() => window.location.reload()}>{t("common.refresh")}</Button>
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function Scene(props: SceneProps) {
  switch (props.state.phase) {
    case "LOBBY":
      return <LobbyScene {...props} />;
    case "INTAKE":
      return props.state.thinking ? <RoundVoteScene {...props} /> : <IntakeScene {...props} />;
    case "ROUND_VOTE":
      return <RoundVoteScene {...props} />;
    case "ANSWERING":
      return <AnsweringScene {...props} />;
    case "VOTING":
    case "REVEAL":
      return props.state.round?.kind ? <VotingScene {...props} /> : <RoundVoteScene {...props} />;
    case "FINALE":
      return <FinaleScene {...props} />;
    default:
      return null;
  }
}

async function toggleFullscreen(): Promise<void> {
  try {
    if (document.fullscreenElement) await document.exitFullscreen();
    else await document.documentElement.requestFullscreen();
  } catch {
    /* fullscreen not allowed here */
  }
}

function OwnerMenu({ state, send }: { state: RoomView; send: SceneProps["send"] }) {
  const { t } = useI18n();
  const [open, setOpen] = useState(false);
  const inGame = ["INTAKE", "ROUND_VOTE", "ANSWERING", "VOTING", "REVEAL"].includes(state.phase);
  const act = (type: string, data: unknown = {}) => {
    send(type, data);
    setOpen(false);
  };
  return (
    <div className={styles.menu}>
      <Button variant="secondary" aria-expanded={open} onClick={() => setOpen((v) => !v)}>
        {t("screen.menu")}
      </Button>
      {open && (
        <ul className={styles.menuList}>
          {state.phase === "LOBBY" && (
            <li>
              <button type="button" onClick={() => act("room.lock", { locked: !state.locked })}>
                {state.locked ? t("screen.openRoom") : t("screen.closeEntry")}
              </button>
            </li>
          )}
          {inGame && !state.paused && (
            <li>
              <button type="button" onClick={() => act("game.pause")}>
                {t("screen.pause")}
              </button>
            </li>
          )}
          {inGame && (
            <li>
              <button type="button" onClick={() => act("game.next")}>
                {t("screen.skipPhase")}
              </button>
            </li>
          )}
          {inGame && (
            <li>
              <button type="button" onClick={() => act("game.end")}>
                {t("screen.endGame")}
              </button>
            </li>
          )}
          <li>
            <button type="button" onClick={() => act("room.close")}>
              {t("screen.closeRoom")}
            </button>
          </li>
        </ul>
      )}
    </div>
  );
}

function Overlays({ state, offset, owner, send }: Pick<SceneProps, "state" | "offset" | "owner" | "send">) {
  const { t } = useI18n();
  if (!state.paused) return null;
  const { reason, welcomeBack } = state.paused;
  return (
    <div className={styles.overlay} role="dialog" aria-modal="true" aria-label={t(`pause.${reason}`)}>
      <div className={styles.overlayCard}>
        {reason === "WAITING_FOR_PLAYERS" && state.waiting ? (
          <>
            <h2>
              {state.waiting.missing.length
                ? t("screen.waitingFor", { names: state.waiting.missing.join(t("common.and")) })
                : t("screen.waitingPlayers")}
            </h2>
            <Countdown deadline={state.waiting.deadline} offset={offset} size="huge" />
            {owner && (
              <Button variant="secondary" onClick={() => send("game.end")}>
                {t("screen.endGame")}
              </Button>
            )}
          </>
        ) : (
          <>
            <h2>{reason === "SCREEN_LOST" && welcomeBack ? t("screen.welcomeBack") : t(`pause.${reason}`)}</h2>
            {owner && (
              <Button big onClick={() => send("game.resume")}>
                {t("common.continue")}
              </Button>
            )}
          </>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- S9

function PartyOver({ state, owner, code }: { state: RoomView | null; owner: boolean; code: string }) {
  const { t } = useI18n();
  useFollowRoomLanguage(state?.settings.language);
  const sessionId = state?.sessionId;
  const landing = `${window.location.origin}${hostYourOwnUrl("S9")}`;
  useEffect(() => forgetSeat(code, owner ? "owner" : "screen"), [code, owner]);
  return (
    <div className={`night ${styles.frame}`}>
      <header className={styles.bar}>
        <Logo />
        <LanguagePicker tone="night" />
      </header>
      <main className={styles.over}>
        <section>
          <h1 className={styles.headline}>{t("screen.thanks")}</h1>
          {owner && sessionId && <Feedback sessionId={sessionId} />}
          {owner ? (
            <ButtonLink to="/new" big>
              {t("new.title")}
            </ButtonLink>
          ) : (
            <Link to="/">{t("screen.backHome")}</Link>
          )}
        </section>
        <section className={styles.overQr}>
          <JoinQr url={landing} />
          <p>{t("common.hostYourOwn")}</p>
        </section>
      </main>
    </div>
  );
}

export function Feedback({ sessionId }: { sessionId: string }) {
  const { t, tp } = useI18n();
  const [rating, setRating] = useState(0);
  const [comment, setComment] = useState("");
  const [state, setState] = useState<"idle" | "sending" | "sent" | "failed">("idle");
  const submit = async () => {
    setState("sending");
    try {
      await api(`/api/games/${sessionId}/feedback`, { method: "POST", body: { rating, comment: comment.trim() || undefined } });
      setState("sent");
    } catch {
      setState("failed");
    }
  };
  if (state === "sent") return <p className={styles.feedbackDone}>{t("screen.feedbackThanks")}</p>;
  if (state === "failed") return <p className={styles.feedbackDone}>{t("screen.feedbackFailed")}</p>;
  return (
    <div className={styles.feedback}>
      <div role="radiogroup" aria-label={t("screen.rateGame")} className={styles.stars}>
        {[1, 2, 3, 4, 5].map((n) => (
          <button
            key={n}
            type="button"
            role="radio"
            aria-checked={rating === n}
            aria-label={tp("screen.stars", n)}
            data-on={n <= rating || undefined}
            onClick={() => setRating(n)}
          >
            ★
          </button>
        ))}
      </div>
      <label className="visually-hidden" htmlFor="feedback-comment">
        {t("screen.comment")}
      </label>
      <textarea
        id="feedback-comment"
        maxLength={500}
        placeholder={t("screen.commentPlaceholder")}
        value={comment}
        onChange={(e) => setComment(e.target.value)}
      />
      <Button variant="secondary" disabled={rating === 0} loading={state === "sending"} onClick={() => void submit()}>
        {t("screen.rate")}
      </Button>
    </div>
  );
}
