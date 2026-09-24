import { Avatar } from "../../../components/game/Avatar";
import { Countdown } from "../../../components/game/Countdown";
import { HostLine } from "../../../components/game/HostLine";
import { KIND_ICONS } from "../../../lib/game/labels";
import { useI18n, type I18n } from "../../../lib/i18n";
import type { RoomView } from "../../../lib/game/types";
import styles from "../Screen.module.css";
import { Scoreboard } from "./Scoreboard";
import { type SceneProps, playerById } from "./shared";

function AudienceBar({ state }: { state: RoomView }) {
  const i18n = useI18n();
  const round = state.round;
  if (state.settings.mode !== "STREAMER" || !round?.options || !round.audienceTotal) return null;
  return (
    <div className={styles.audienceBar} aria-label={i18n.t("voting.viewerVotes")}>
      {round.options.map((o) => {
        const share = Math.round(((o.audienceVotes ?? 0) * 100) / round.audienceTotal!);
        return (
          <span key={o.id} style={{ flexGrow: Math.max(share, 4) }}>
            {optionLabel(state, o.id, i18n.t)} {share}%
          </span>
        );
      })}
    </div>
  );
}

function optionLabel(state: RoomView, id: string, t: I18n["t"]): string {
  if (state.round?.kind === "ANSWER_DUEL") return id;
  if (state.round?.kind === "TRUTH_OR_AI") return id === "truth" ? t("common.truth") : t("common.ai");
  return playerById(state, id)?.name ?? id;
}

function Voters({ state }: { state: RoomView }) {
  const { t } = useI18n();
  return (
    <div className={styles.voters} aria-label={t("voting.voted")}>
      {(state.round?.voters ?? []).map((id) => {
        const p = playerById(state, id);
        return p ? <Avatar key={id} player={p} size="small" /> : null;
      })}
    </div>
  );
}

export function VotingScene(props: SceneProps) {
  const { state, offset, speaking } = props;
  const { t } = useI18n();
  const round = state.round!;
  const reveal = state.phase === "REVEAL";
  return (
    <div className={styles.center}>
      <div className={styles.topRow}>
        <p className={styles.kicker}>
          {KIND_ICONS[round.kind!]} {t(`kind.${round.kind!}.title`)}
          {round.kind === "ANSWER_DUEL" && round.duelCount
            ? ` \u00b7 ${t("voting.duel", { i: (round.duelIndex ?? 0) + 1, n: round.duelCount })}`
            : ""}
        </p>
        {!reveal && <Countdown deadline={state.deadline} offset={offset} size="huge" />}
      </div>
      {round.kind === "ANSWER_DUEL" && <DuelBoard {...props} />}
      {round.kind === "WHO_OF_US" && <WhoBoard {...props} />}
      {round.kind === "TRUTH_OR_AI" && <TruthBoard {...props} />}
      {!reveal && <Voters state={state} />}
      <AudienceBar state={state} />
      {reveal && (
        <HostLine text={state.host?.text} skipped={state.host?.skipped} speaking={speaking} size="large" thinking={state.thinking} />
      )}
      {reveal && round.lastOfRound && <Scoreboard players={state.players ?? []} />}
    </div>
  );
}

function Points({ value }: { value?: number }) {
  if (!value) return null;
  return <span className={styles.points}>+{value}</span>;
}

function DuelBoard({ state }: SceneProps) {
  const { t, tp } = useI18n();
  const round = state.round!;
  const reveal = state.phase === "REVEAL";
  const total = (round.options ?? []).reduce((n, o) => n + (o.votes ?? 0), 0);
  return (
    <>
      <h1 className={styles.prompt}>{round.prompt}</h1>
      <div className={styles.duel}>
        {(round.options ?? []).map((o) => {
          const author = reveal ? playerById(state, o.authorId) : undefined;
          return (
            <div key={o.id} className={styles.answer} data-winner={reveal && o.winner ? true : undefined}>
              <span className={styles.answerLetter}>{o.id}</span>
              <p className={styles.answerText}>{o.text}</p>
              {reveal && (
                <div className={styles.answerMeta}>
                  {author && <Avatar player={author} size="small" />}
                  <span>{author?.name}</span>
                  <span className={styles.voteCount}>
                    {tp("votes.count", o.votes ?? 0)}
                    {total > 0 ? ` \u00b7 ${Math.round(((o.votes ?? 0) * 100) / total)}%` : ""}
                  </span>
                  <Points value={o.points} />
                </div>
              )}
            </div>
          );
        })}
      </div>
      {reveal && round.landslide && <p className={styles.landslide}>{t("common.wipeout")}</p>}
    </>
  );
}

function WhoBoard({ state }: SceneProps) {
  const round = state.round!;
  const reveal = state.phase === "REVEAL";
  return (
    <>
      <h1 className={styles.prompt}>{round.question}</h1>
      <ul className={styles.whoGrid}>
        {(round.options ?? []).map((o) => {
          const p = playerById(state, o.id);
          return (
            <li key={o.id} data-winner={reveal && o.winner ? true : undefined}>
              {p && <Avatar player={p} size="large" badge={reveal ? String(o.votes ?? 0) : undefined} />}
              <span>{o.text}</span>
              {reveal && <Points value={round.points?.[o.id]} />}
            </li>
          );
        })}
      </ul>
    </>
  );
}

function TruthBoard({ state }: SceneProps) {
  const { t, tp } = useI18n();
  const round = state.round!;
  const reveal = state.phase === "REVEAL";
  const subject = playerById(state, round.subjectId);
  return (
    <>
      <p className={styles.kicker}>{t("truth.factAbout", { name: subject?.name ?? "" })}</p>
      <h1 className={styles.prompt}>{round.statement}</h1>
      {subject && (
        <p className={styles.subject}>
          <Avatar player={subject} /> {t("truth.knows", { name: subject.name })}
        </p>
      )}
      <div className={styles.duel}>
        {(round.options ?? []).map((o) => (
          <div
            key={o.id}
            className={styles.answer}
            data-winner={reveal && o.correct ? true : undefined}
            data-wrong={reveal && !o.correct ? true : undefined}
          >
            <p className={styles.answerText}>{o.id === "truth" ? t("common.truth") : t("common.aiInvention")}</p>
            {reveal && <span className={styles.voteCount}>{tp("votes.count", o.votes ?? 0)}</span>}
          </div>
        ))}
      </div>
    </>
  );
}

// ---------------------------------------------------------------- S8 finale
