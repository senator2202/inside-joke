import { Avatar } from "../../../components/game/Avatar";
import type { PlayerView } from "../../../lib/game/types";
import styles from "../Screen.module.css";

export function Scoreboard({ players, compact }: { players: PlayerView[]; compact?: boolean }) {
  const sorted = [...players].sort((a, b) => b.score - a.score);
  return (
    <ol className={`${styles.scores} ${compact ? styles.scoresCompact : ""}`}>
      {sorted.map((p) => (
        <li key={p.id}>
          <Avatar player={p} size="small" />
          <span className={styles.scoreName}>{p.name}</span>
          <span className={styles.scoreValue}>{p.score}</span>
        </li>
      ))}
    </ol>
  );
}

// ---------------------------------------------------------------- S1 lobby
