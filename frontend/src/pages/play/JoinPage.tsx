import { useEffect, useState, type FormEvent } from "react";
import { Link, Navigate, useNavigate, useParams } from "react-router";
import { Button } from "../../components/Button";
import { LanguagePicker } from "../../components/LanguagePicker";
import { Logo } from "../../components/Logo";
import { useFollowRoomLanguage, useI18n } from "../../lib/i18n";
import { api, isApiError } from "../../lib/api";
import { loadPref, loadSeat, saveSeat, savePref } from "../../lib/game/storage";
import type { RoomStatus } from "../../lib/game/types";
import styles from "./Play.module.css";

/** Same list the server accepts (GameEngineService.EMOJIS). */
export const EMOJIS = [
  "🦊",
  "🐙",
  "🦄",
  "🐸",
  "🐼",
  "🦉",
  "🐯",
  "🐨",
  "🦖",
  "🐝",
  "🦩",
  "🐳",
  "🦔",
  "🐲",
  "🦜",
  "🐢",
  "🦀",
  "🐧",
  "🦦",
  "🐞",
  "🍄",
  "🌵",
  "🍩",
  "🎸",
];
const CODE_CHARS = /[^BCDFGHJKLMNPQRSTVWXZ]/g;

function randomEmoji(except?: string): string {
  const pool = EMOJIS.filter((e) => e !== except);
  return pool[Math.floor(Math.random() * pool.length)]!;
}

type Lookup = { state: "idle" } | { state: "checking" } | { state: "found"; room: RoomStatus } | { state: "error"; message: string };
type Result = { code: string } & ({ room: RoomStatus } | { message: string });

/** P1: join in 10 seconds, no sign-up. */
export function JoinPage() {
  const fromLink = (useParams().code ?? "").toUpperCase().replace(CODE_CHARS, "").slice(0, 4);
  const navigate = useNavigate();
  const i18n = useI18n();
  const { t } = i18n;
  const [code, setCode] = useState(fromLink);
  const [name, setName] = useState(() => loadPref("name") ?? "");
  const [emoji, setEmoji] = useState(() => loadPref("emoji") ?? randomEmoji());
  const [result, setResult] = useState<Result | null>(null);
  const [joining, setJoining] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (code.length !== 4) return;
    const ctl = new AbortController();
    api<RoomStatus>(`/api/rooms/${code}`, { signal: ctl.signal }).then(
      (room) => setResult({ code, room }),
      (e: unknown) => {
        if (e instanceof DOMException && e.name === "AbortError") return;
        setResult({ code, message: isApiError(e) ? e.code : "NETWORK" });
      },
    );
    return () => ctl.abort();
  }, [code]);

  const lookup: Lookup =
    code.length !== 4
      ? { state: "idle" }
      : result?.code !== code
        ? { state: "checking" }
        : "room" in result
          ? { state: "found", room: result.room }
          : { state: "error", message: i18n.error(result.message) };

  useFollowRoomLanguage(lookup.state === "found" ? lookup.room.language : null);

  if (fromLink && loadSeat(fromLink, "player")) return <Navigate to={`/play/${fromLink}`} replace />;

  const room = lookup.state === "found" ? lookup.room : null;
  const blocked =
    room && !room.joinable
      ? room.locked
        ? i18n.error("ROOM_LOCKED")
        : room.full
          ? i18n.error("ROOM_FULL")
          : i18n.error("ROOM_IN_PROGRESS")
      : null;
  const trimmed = name.trim();
  const canJoin = room !== null && !blocked && trimmed.length > 0 && [...trimmed].length <= 12 && !joining;

  const join = async (e: FormEvent) => {
    e.preventDefault();
    if (!canJoin) return;
    setJoining(true);
    setError(null);
    try {
      const joined = await api<{ playerId: string; playerToken: string }>(`/api/rooms/${code}/players`, {
        method: "POST",
        body: { name: trimmed, emoji },
      });
      savePref("name", trimmed);
      savePref("emoji", emoji);
      saveSeat(code, { token: joined.playerToken, kind: "player", playerId: joined.playerId });
      void navigate(`/play/${code}`);
    } catch (err) {
      setJoining(false);
      setError(i18n.error(isApiError(err) ? err.code : "NETWORK"));
    }
  };

  return (
    <div className={`night ${styles.phone}`}>
      <header className={styles.joinHeader}>
        <Logo />
        <LanguagePicker tone="night" />
      </header>
      <form className={styles.join} onSubmit={(e) => void join(e)} noValidate>
        <h1 className={styles.bigTitle}>{t("join.title")}</h1>
        <label className={styles.label} htmlFor="join-code">
          {t("join.code")}
        </label>
        <input
          id="join-code"
          className={styles.codeInput}
          value={code}
          inputMode="text"
          autoCapitalize="characters"
          autoComplete="off"
          spellCheck={false}
          maxLength={4}
          placeholder="ABCD"
          aria-describedby="join-code-status"
          onChange={(e) => setCode(e.target.value.toUpperCase().replace(CODE_CHARS, "").slice(0, 4))}
        />
        <p id="join-code-status" className={styles.status} aria-live="polite" data-bad={lookup.state === "error" || !!blocked || undefined}>
          {lookup.state === "checking" && t("common.checking")}
          {lookup.state === "found" && !blocked && t("join.found")}
          {lookup.state === "error" && lookup.message}
          {blocked}
        </p>
        {blocked && room?.audienceOpen && room.audienceKey && (
          <Link className={styles.link} to={`/w/${room.audienceKey}`}>
            {t("join.watch")}
          </Link>
        )}

        <label className={styles.label} htmlFor="join-name">
          {t("join.name")}
        </label>
        <div className={styles.nameRow}>
          <button
            type="button"
            className={styles.emoji}
            onClick={() => setEmoji((e) => randomEmoji(e))}
            aria-label={t("join.avatarAria", { emoji })}
          >
            {emoji}
          </button>
          <input
            id="join-name"
            className={styles.input}
            value={name}
            maxLength={12}
            autoComplete="nickname"
            placeholder={t("join.namePlaceholder")}
            onChange={(e) => setName(e.target.value)}
          />
        </div>
        <p className={styles.small}>{t("join.noSignup")}</p>

        {error && (
          <p className={styles.error} role="alert">
            {error}
          </p>
        )}
        <Button type="submit" big block disabled={!canJoin} loading={joining} loadingLabel={t("join.joining")}>
          {t("join.submit")}
        </Button>

        <div className={styles.joinLinks}>
          {code.length === 4 && (
            <Link className={styles.link} to={`/view/${code}`}>
              {t("join.remote")}
            </Link>
          )}
          {room?.audienceOpen && room.audienceKey && !blocked && (
            <Link className={styles.link} to={`/w/${room.audienceKey}`}>
              {t("join.viewer")}
            </Link>
          )}
        </div>
      </form>
    </div>
  );
}
