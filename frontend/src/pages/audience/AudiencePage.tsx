import { useState } from "react";
import { Link, useParams } from "react-router";
import { Button } from "../../components/Button";
import { Countdown } from "../../components/game/Countdown";
import { Logo } from "../../components/Logo";
import { api, isApiError } from "../../lib/api";
import { hostYourOwnUrl } from "../../lib/analytics";
import { loadSeat, saveSeat } from "../../lib/game/storage";
import type { RoomView } from "../../lib/game/types";
import { useGame } from "../../lib/game/useGame";
import { useFollowRoomLanguage, useI18n, type I18n } from "../../lib/i18n";
import { LanguagePicker } from "../../components/LanguagePicker";
import { CantConnect, RoomGone } from "../system/GameSystemScreens";
import styles from "../play/Play.module.css";

/** A1-A4: stream viewers vote from their own devices, no name needed. */
export function AudiencePage() {
  // The link carries the viewers' key, not the room code (a streamer may hide the code).
  const code = (useParams().key ?? "").toUpperCase();
  const i18n = useI18n();
  const { t } = i18n;
  const [token, setToken] = useState(() => loadSeat(code, "audience")?.token ?? null);
  const [joining, setJoining] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const join = async () => {
    setJoining(true);
    setError(null);
    try {
      const r = await api<{ audienceToken: string }>(`/api/audiences/${encodeURIComponent(code)}/viewers`, { method: "POST" });
      saveSeat(code, { token: r.audienceToken, kind: "audience" });
      setToken(r.audienceToken);
    } catch (e) {
      setJoining(false);
      const c = isApiError(e) ? e.code : undefined;
      setError(c === "NOT_STREAMER_MODE" ? t("audience.notForViewers") : c === "ROOM_NOT_FOUND" ? t("audience.noRoom") : i18n.error(c));
    }
  };

  if (token) return <Viewer token={token} />;
  return (
    <div className={`night ${styles.phone}`}>
      <header className={styles.joinHeader}>
        <Logo />
        <LanguagePicker tone="night" />
      </header>
      <div className={styles.join}>
        <h1 className={styles.bigTitle}>{t("audience.title")}</h1>
        <p className={styles.lead}>{t("audience.noName")}</p>
        {error && (
          <p className={styles.error} role="alert">
            {error}
          </p>
        )}
        <Button big block loading={joining} onClick={() => void join()}>
          {t("audience.join")}
        </Button>
      </div>
    </div>
  );
}

function Viewer({ token }: { token: string }) {
  const { state, status, clockOffset, request, retry } = useGame(token);
  const { t } = useI18n();
  useFollowRoomLanguage(state?.settings.language);
  const [picked, setPicked] = useState<string | null>(null);
  const [step, setStep] = useState("");
  if (status === "unreachable") return <CantConnect onRetry={retry} />;
  if (status === "gone") return <RoomGone />;
  if (!state)
    return (
      <div className={`night ${styles.phone} ${styles.centered}`}>
        <p>{t("common.connecting")}</p>
      </div>
    );

  const key = `${state.round?.n}/${state.round?.duelIndex ?? 0}/${state.phase}`;
  if (key !== step) {
    setStep(key);
    if (state.phase !== "REVEAL") setPicked(null);
  }
  const over = status === "closed" || state.phase === "FINALE" || state.phase === "CLOSED";
  return (
    <div className={`night ${styles.phone}`}>
      <header className={styles.top}>
        <Logo to={null} />
        <span className={styles.me}>{t("audience.viewer")}</span>
        <LanguagePicker tone="night" />
      </header>
      <main className={styles.main}>
        <div className={styles.scene}>
          {over ? (
            <>
              <h1 className={styles.bigTitle}>{t("common.gameOver")}</h1>
              <Link className={styles.link} to={hostYourOwnUrl("A4")}>
                {t("common.hostYourOwn")}
              </Link>
            </>
          ) : state.phase === "VOTING" && state.round?.options ? (
            <AudienceVote
              state={state}
              offset={clockOffset}
              voted={state.audience?.voted === true || picked !== null}
              onVote={(id) => {
                setPicked(id);
                void request("audience.vote", { optionId: id }).catch(() => undefined);
              }}
            />
          ) : state.phase === "REVEAL" && state.round?.options ? (
            <AudienceResult state={state} />
          ) : (
            <>
              <p className={styles.kicker}>{t(`phase.${state.phase}`)}</p>
              <h1 className={styles.bigTitle}>{t("audience.opensNext")}</h1>
            </>
          )}
        </div>
      </main>
    </div>
  );
}

function optionText(state: RoomView, id: string, text: string | undefined, t: I18n["t"]): string {
  if (state.round?.kind === "TRUTH_OR_AI") return id === "truth" ? t("common.truth") : t("common.aiInvention");
  return text ?? id;
}

function AudienceVote({ state, offset, voted, onVote }: { state: RoomView; offset: number; voted: boolean; onVote: (id: string) => void }) {
  const { t } = useI18n();
  const round = state.round!;
  return (
    <>
      <div className={styles.row}>
        <p className={styles.kicker}>{t(`kind.${round.kind!}.title`)}</p>
        <Countdown deadline={state.deadline} offset={offset} />
      </div>
      <p className={styles.question}>{round.prompt ?? round.question ?? round.statement}</p>
      {voted ? (
        <p className={styles.bigTitle}>{t("common.voteAccepted")}</p>
      ) : (
        <div className={styles.options}>
          {round.options!.map((o) => (
            <button key={o.id} type="button" className={styles.option} onClick={() => onVote(o.id)}>
              {round.kind === "ANSWER_DUEL" && <span className={styles.optionLetter}>{o.id}</span>}
              {optionText(state, o.id, o.text, t)}
            </button>
          ))}
        </div>
      )}
    </>
  );
}

function AudienceResult({ state }: { state: RoomView }) {
  const { t } = useI18n();
  const options = state.round!.options!;
  const total = options.reduce((n, o) => n + (o.audienceVotes ?? 0), 0);
  const top = [...options].sort((a, b) => (b.audienceVotes ?? 0) - (a.audienceVotes ?? 0))[0];
  if (!top || total === 0) return <h1 className={styles.bigTitle}>{t("audience.noVotes")}</h1>;
  const share = Math.round(((top.audienceVotes ?? 0) * 100) / total);
  const label = state.round?.kind === "ANSWER_DUEL" ? t("audience.answer", { id: top.id }) : optionText(state, top.id, top.text, t);
  return <h1 className={styles.bigTitle}>{t("audience.picked", { label, share })}</h1>;
}
