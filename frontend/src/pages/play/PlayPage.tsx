import { useEffect, useState, type ReactNode } from "react";
import { Navigate, useNavigate, useParams } from "react-router";
import { Button, ButtonLink } from "../../components/Button";
import { Avatar } from "../../components/game/Avatar";
import { ConnectionBanner } from "../../components/game/ConnectionBanner";
import { hostYourOwnUrl, track } from "../../lib/analytics";
import { forgetSeat, loadSeat } from "../../lib/game/storage";
import type { RoomView } from "../../lib/game/types";
import { useGame } from "../../lib/game/useGame";
import { useFollowRoomLanguage, useI18n } from "../../lib/i18n";
import { LanguagePicker } from "../../components/LanguagePicker";
import { CantConnect } from "../system/GameSystemScreens";
import {
  AnswerPhone,
  FinalePhone,
  IntakePhone,
  KindVotePhone,
  LobbyPhone,
  QuickRating,
  RevealPhone,
  SecretInvite,
  SecretSheet,
  VotePhone,
  Waiting,
  type PhoneProps,
} from "./scenes";
import styles from "./Play.module.css";

/** P2-P11: the player's phone, driven entirely by the server's snapshots. */
export function PlayPage() {
  const code = (useParams().code ?? "").toUpperCase();
  const seat = loadSeat(code, "player");
  if (!seat) return <Navigate to={`/j/${code}`} replace />;
  return <Phone code={code} token={seat.token} />;
}

function secretAllowed(state: RoomView): boolean {
  const you = state.you;
  if (state.secretsOpen === false || state.phase === "CLOSED") return false;
  if (state.phase === "INTAKE" && you.intakeNeeded) return false;
  if (state.phase === "ANSWERING" && (you.assignments ?? []).some((a) => !a.answer)) return false;
  if (state.phase === "VOTING" && you.canVote && !you.voted) return false;
  return true;
}

function Phone({ code, token }: { code: string; token: string }) {
  const { state, status, clockOffset, request, retry } = useGame(token);
  const { t } = useI18n();
  useFollowRoomLanguage(state?.settings.language);
  const navigate = useNavigate();
  const [sheet, setSheet] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
  const [invited, setInvited] = useState(false);
  const [rated, setRated] = useState(false);

  useEffect(() => {
    if (status === "gone" || status === "kicked" || status === "closed") forgetSeat(code, "player");
  }, [status, code]);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 3500);
    return () => clearTimeout(t);
  }, [toast]);

  const hostYourOwn = (source: string) => {
    track("guest_host_cta_clicked", { source });
    void navigate(hostYourOwnUrl(source));
  };

  if (status === "unreachable") return <CantConnect onRetry={retry} />;
  if (status === "kicked") {
    return (
      <End title={t("phone.removed")}>
        <ButtonLink to="/join" big block>
          {t("phone.joinAnother")}
        </ButtonLink>
      </End>
    );
  }
  if (status === "gone" || status === "closed") {
    return (
      <End title={status === "gone" && !state ? t("phone.roomGone") : t("phone.partyOver")}>
        {status === "closed" && <QuickRating rated={rated} onRate={() => setRated(true)} />}
        <Button big block onClick={() => hostYourOwn("P10")}>
          {t("common.hostYourOwn")}
        </Button>
        <ButtonLink to="/join" variant="secondary" block>
          {t("phone.joinAnother")}
        </ButtonLink>
      </End>
    );
  }
  if (!state) {
    return (
      <div className={`night ${styles.phone} ${styles.centered}`}>
        <p>{t("common.connecting")}</p>
      </div>
    );
  }

  const props: PhoneProps = { state, offset: clockOffset, request };
  const openSecret = () => setSheet(true);
  const you = state.you;
  const captain = you.role === "CAPTAIN";

  let scene;
  switch (state.phase) {
    case "LOBBY":
      scene = <LobbyPhone {...props} onSecret={secretAllowed(state) ? openSecret : undefined} />;
      break;
    case "INTAKE":
      scene = you.intakeNeeded ? (
        <IntakePhone {...props} />
      ) : !invited && secretAllowed(state) ? (
        <SecretInvite
          onSecret={() => {
            setInvited(true);
            openSecret();
          }}
          onWait={() => setInvited(true)}
        />
      ) : (
        <Waiting text={t("phone.waitingOthers")} onSecret={secretAllowed(state) ? openSecret : undefined} />
      );
      break;
    case "ROUND_VOTE":
      scene = state.thinking ? <Waiting text={t("phone.hostChoosing")} /> : <KindVotePhone {...props} />;
      break;
    case "ANSWERING":
      scene = state.thinking ? (
        <Waiting text={t("phone.hostReading")} />
      ) : (
        <AnswerPhone {...props} onSecret={secretAllowed(state) ? openSecret : undefined} />
      );
      break;
    case "VOTING":
      scene = <VotePhone {...props} />;
      break;
    case "REVEAL":
      scene = <RevealPhone {...props} />;
      break;
    case "FINALE":
      scene = <FinalePhone {...props} rated={rated} onRated={() => setRated(true)} onHostYourOwn={() => hostYourOwn("P9")} />;
      break;
    default:
      scene = null;
  }

  const captainAction = !captain ? null : state.phase === "LOBBY" ? (
    <Button
      big
      block
      disabled={(state.players?.length ?? 0) < 3 || !!state.paywall}
      loading={state.starting}
      onClick={() => void request("game.start").catch(() => setToast(t("phone.startFailed")))}
    >
      {t("phone.start")}
    </Button>
  ) : state.phase === "REVEAL" ? (
    <Button big block onClick={() => void request("game.next").catch(() => undefined)}>
      {t("common.next")}
    </Button>
  ) : state.phase === "FINALE" ? (
    <Button big block onClick={() => void request("game.again").catch(() => undefined)}>
      {t("common.playAgain")}
    </Button>
  ) : null;

  return (
    <div className={`night ${styles.phone}`}>
      <ConnectionBanner status={status} onRetry={retry} />
      <header className={styles.top}>
        {you.emoji && <Avatar player={{ emoji: you.emoji, name: you.name ?? "", connected: status === "live", captain }} size="small" />}
        <span className={styles.me}>{you.name}</span>
        <span className={styles.score}>{you.score ?? 0}</span>
        <span
          className={styles.dot}
          data-live={status === "live" || undefined}
          aria-label={status === "live" ? t("phone.connected") : t("screen.reconnecting")}
        />
        <LanguagePicker tone="night" />
      </header>
      {state.paused && (
        <p className={styles.pauseBar} role="status">
          {state.paused.reason === "WAITING_FOR_PLAYERS" && state.waiting
            ? t("phone.waitingFor", { names: state.waiting.missing.join(t("common.and")) })
            : t(`pause.${state.paused.reason}`)}
        </p>
      )}
      <main className={styles.main}>{scene}</main>
      {captainAction && <footer className={styles.captainBar}>{captainAction}</footer>}
      {secretAllowed(state) && !sheet && (
        <button type="button" className={styles.fab} onClick={openSecret} aria-label={t("phone.giveSecretAria")}>
          {t("phone.secretButton")}
        </button>
      )}
      {sheet && (
        <SecretSheet
          state={state}
          request={request}
          onClose={() => setSheet(false)}
          onAccepted={() => {
            setSheet(false);
            setToast(t("phone.secretAccepted"));
          }}
        />
      )}
      {toast && (
        <p className={styles.toast} role="status">
          {toast}
        </p>
      )}
    </div>
  );
}

function End({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className={`night ${styles.phone} ${styles.centered}`}>
      <div className={styles.scene}>
        <h1 className={styles.bigTitle}>{title}</h1>
        {children}
      </div>
    </div>
  );
}
