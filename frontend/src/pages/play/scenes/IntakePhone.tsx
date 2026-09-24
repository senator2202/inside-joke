import { useState } from "react";
import { Button } from "../../../components/Button";
import { Countdown } from "../../../components/game/Countdown";
import { useI18n } from "../../../lib/i18n";
import { GameError } from "../../../lib/game/types";
import styles from "../Play.module.css";
import { type PhoneProps, codeOf, useBeforeDeadline } from "./shared";

const EXAMPLES = ["phone.example1", "phone.example2", "phone.example3"] as const;

export function IntakePhone({ state, offset, request }: PhoneProps) {
  const i18n = useI18n();
  const { t } = i18n;
  const questions = state.intake?.questions ?? [];
  const [answers, setAnswers] = useState(["", "", ""]);
  const [step, setStep] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);

  const submit = async (values: string[]) => {
    setSending(true);
    setError(null);
    try {
      await request("intake.submit", { answers: values.map((a) => a.trim()) });
    } catch (e) {
      if (codeOf(e) === "MODERATION_BLOCKED" && e instanceof GameError) {
        const rejected = (e.details.rejected as number[] | undefined) ?? [0];
        setStep(rejected[0] ?? 0);
        setError(t("phone.intakeRejected"));
      } else {
        setError(i18n.error(codeOf(e)));
      }
    } finally {
      setSending(false);
    }
  };

  // Time's up with text typed: send what we have, unanswered questions stay empty.
  useBeforeDeadline(state.deadline, offset, () => {
    if (answers.some((a) => a.trim())) void submit(answers);
  });

  const value = answers[step] ?? "";
  const last = step === questions.length - 1;
  const next = () => {
    if (!value.trim()) return;
    if (last) void submit(answers);
    else setStep(step + 1);
  };
  return (
    <div className={styles.scene}>
      <div className={styles.row}>
        <p className={styles.kicker}>{t("phone.stepOf", { n: step + 1, total: questions.length })}</p>
        <Countdown deadline={state.deadline} offset={offset} />
      </div>
      <label className={styles.question} htmlFor="intake-answer">
        {questions[step]}
      </label>
      <textarea
        id="intake-answer"
        className={styles.textarea}
        maxLength={120}
        value={value}
        placeholder={t("phone.eg", { example: t(EXAMPLES[step] ?? "phone.example1") })}
        onChange={(e) => setAnswers((a) => a.map((x, i) => (i === step ? e.target.value : x)))}
      />
      <p className={styles.counter}>{value.length}/120</p>
      {error && (
        <p className={styles.error} role="alert">
          {error}
        </p>
      )}
      <div className={styles.dots} aria-hidden="true">
        {questions.map((_, i) => (
          <span key={i} data-on={i <= step || undefined} />
        ))}
      </div>
      <Button big block onClick={next} disabled={!value.trim()} loading={sending}>
        {last ? t("common.done") : t("common.next")}
      </Button>
    </div>
  );
}

// ---------------------------------------------------------------- P3b
