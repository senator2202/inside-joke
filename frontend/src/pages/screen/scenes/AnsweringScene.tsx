import { useEffect, useState } from "react";
import { Avatar } from "../../../components/game/Avatar";
import { Countdown } from "../../../components/game/Countdown";
import { HostLine } from "../../../components/game/HostLine";
import { useI18n } from "../../../lib/i18n";
import styles from "../Screen.module.css";
import { type SceneProps, playerById } from "./shared";

const NUDGES = ["answering.nudge1", "answering.nudge2"] as const;

export function AnsweringScene({ state, offset }: SceneProps) {
  const { t } = useI18n();
  const progress = state.round?.progress ?? [];
  const [nudge, setNudge] = useState<(typeof NUDGES)[number] | null>(null);
  useEffect(() => {
    if (!state.deadline) return;
    const wait = state.deadline - (Date.now() + offset) - 20_000;
    const t = setTimeout(() => setNudge(NUDGES[Math.floor(Math.random() * NUDGES.length)]!), Math.max(0, wait));
    return () => clearTimeout(t);
  }, [state.deadline, offset]);
  return (
    <div className={styles.center}>
      <div className={styles.topRow}>
        <h1 className={styles.headline}>{t("answering.headline")}</h1>
        <Countdown deadline={state.thinking ? undefined : state.deadline} offset={offset} size="huge" />
      </div>
      <ul className={styles.progressGrid}>
        {progress.map((row) => {
          const p = playerById(state, row.playerId);
          if (!p) return null;
          const label =
            row.answered >= row.total
              ? "✓"
              : row.answered === 0
                ? t("answering.typing")
                : t("answering.sent", { a: row.answered, t: row.total });
          return (
            <li key={row.playerId}>
              <Avatar player={p} size="large" badge={label} />
              <span>{p.name}</span>
            </li>
          );
        })}
      </ul>
      {state.thinking ? <HostLine thinking /> : nudge && <HostLine text={t(nudge)} />}
    </div>
  );
}

// ---------------------------------------------------------------- S5 voting and S6 reveal
