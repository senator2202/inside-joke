import { useI18n } from "../../lib/i18n";
import type { PlayerView } from "../../lib/game/types";
import styles from "./game.module.css";

export function Avatar({
  player,
  size = "normal",
  badge,
}: {
  player: Pick<PlayerView, "emoji" | "name" | "connected" | "captain">;
  size?: "small" | "normal" | "large";
  badge?: string;
}) {
  const { t } = useI18n();
  return (
    <span className={`${styles.avatar} ${styles[size]}`} data-off={!player.connected || undefined} title={player.name}>
      <span aria-hidden="true">{player.emoji}</span>
      {player.captain && (
        <span className={styles.crown} aria-label={t("avatar.captain")}>
          👑
        </span>
      )}
      {badge && <span className={styles.badge}>{badge}</span>}
    </span>
  );
}

export function PlayerChip({ player, detail }: { player: PlayerView; detail?: string }) {
  return (
    <span className={styles.chip} data-off={!player.connected || undefined}>
      <Avatar player={player} size="small" />
      <span className={styles.chipName}>{player.name}</span>
      {detail && <span className={styles.chipDetail}>{detail}</span>}
    </span>
  );
}
