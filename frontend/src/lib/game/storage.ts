/**
 * Room tokens survive reloads and locked phones: they live in localStorage per room code.
 * Nothing secret about the game is stored here, only the seat.
 */
export type SeatKind = "owner" | "player" | "screen" | "audience";

export interface Seat {
  token: string;
  kind: SeatKind;
  playerId?: string;
  savedAt: number;
}

const PREFIX = "ij.room.";
const MAX_AGE_MS = 6 * 60 * 60 * 1000;

function safe<T>(fn: () => T, fallback: T): T {
  try {
    return fn();
  } catch {
    return fallback;
  }
}

function key(code: string, kind: SeatKind): string {
  return `${PREFIX}${code.toUpperCase()}.${kind}`;
}

export function saveSeat(code: string, seat: Omit<Seat, "savedAt">): void {
  safe(() => localStorage.setItem(key(code, seat.kind), JSON.stringify({ ...seat, savedAt: Date.now() })), undefined);
}

export function loadSeat(code: string, kind: SeatKind): Seat | null {
  return safe(() => {
    const raw = localStorage.getItem(key(code, kind));
    if (!raw) return null;
    const seat = JSON.parse(raw) as Seat;
    if (Date.now() - seat.savedAt > MAX_AGE_MS) {
      localStorage.removeItem(key(code, kind));
      return null;
    }
    return seat;
  }, null);
}

export function forgetSeat(code: string, kind: SeatKind): void {
  safe(() => localStorage.removeItem(key(code, kind)), undefined);
}

/** Small per-device preferences: the last name used to join and the last party settings. */
export function loadPref(name: string): string | null {
  return safe(() => localStorage.getItem(`ij.pref.${name}`), null);
}

export function savePref(name: string, value: string): void {
  safe(() => localStorage.setItem(`ij.pref.${name}`, value), undefined);
}

/** Unsent answer drafts, so a reload or a dropped connection never loses what the player typed. */
export function loadDraft(duelId: string): string {
  return safe(() => sessionStorage.getItem(`ij.draft.${duelId}`) ?? "", "");
}

export function saveDraft(duelId: string, text: string): void {
  safe(() => (text ? sessionStorage.setItem(`ij.draft.${duelId}`, text) : sessionStorage.removeItem(`ij.draft.${duelId}`)), undefined);
}
