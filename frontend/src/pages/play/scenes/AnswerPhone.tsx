import { useState } from "react";
import { Button } from "../../../components/Button";
import { Countdown } from "../../../components/game/Countdown";
import { useI18n } from "../../../lib/i18n";
import { loadDraft, saveDraft } from "../../../lib/game/storage";
import styles from "../Play.module.css";
import { Waiting } from "./Waiting";
import { type PhoneProps, codeOf, useBeforeDeadline } from "./shared";

export function AnswerPhone({ state, offset, request, onSecret }: PhoneProps & { onSecret?: () => void }) {
  const i18n = useI18n();
  const { t } = i18n;
  const assignments = state.you.assignments ?? [];
  const [sent, setSent] = useState<Record<string, "sending" | "sent">>({});
  const [error, setError] = useState<string | null>(null);
  const pending = assignments.filter((a) => !a.answer && !sent[a.duelId]);
  const current = pending[0];
  const [text, setText] = useState(() => (current ? loadDraft(current.duelId) : ""));
  const [shownFor, setShownFor] = useState(current?.duelId);

  if (current?.duelId !== shownFor) {
    setShownFor(current?.duelId);
    setText(current ? loadDraft(current.duelId) : "");
  }

  const deliver = (duelId: string, value: string) => {
    setSent((s) => ({ ...s, [duelId]: "sending" }));
    saveDraft(duelId, value);
    request("answer.submit", { duelId, text: value.trim() }).then(
      () => {
        saveDraft(duelId, "");
        setSent((s) => ({ ...s, [duelId]: "sent" }));
      },
      (e: unknown) => {
        setSent((s) => {
          const copy = { ...s };
          delete copy[duelId];
          return copy;
        });
        setError(codeOf(e) === "MODERATION_BLOCKED" ? t("phone.answerBlocked") : i18n.error(codeOf(e)));
      },
    );
  };

  // Time's up with a draft typed: it is sent automatically.
  useBeforeDeadline(state.deadline, offset, () => {
    for (const a of assignments) {
      if (a.answer || sent[a.duelId]) continue;
      const draft = a.duelId === current?.duelId ? text : loadDraft(a.duelId);
      if (draft.trim()) deliver(a.duelId, draft);
    }
  });

  if (!current) {
    const waitingForNetwork = Object.values(sent).includes("sending");
    return <Waiting text={waitingForNetwork ? t("phone.sendingAnswers") : t("phone.answersSent")} onSecret={onSecret} />;
  }
  const index = assignments.findIndex((a) => a.duelId === current.duelId);
  return (
    <div className={styles.scene}>
      <div className={styles.row}>
        <p className={styles.kicker}>{t("phone.promptOf", { i: index + 1, n: assignments.length })}</p>
        <Countdown deadline={state.deadline} offset={offset} />
      </div>
      <label className={styles.question} htmlFor="answer">
        {current.prompt}
      </label>
      <textarea
        id="answer"
        className={styles.textarea}
        maxLength={120}
        value={text}
        autoFocus
        onChange={(e) => {
          setText(e.target.value);
          saveDraft(current.duelId, e.target.value);
          setError(null);
        }}
      />
      <p className={styles.counter}>{t("phone.counterFunny", { len: text.length })}</p>
      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
      <Button big block disabled={!text.trim()} onClick={() => deliver(current.duelId, text)}>
        {t("phone.send")}
      </Button>
    </div>
  );
}

// ---------------------------------------------------------------- P7 voting
