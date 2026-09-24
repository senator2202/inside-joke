/** Wire types of the game protocol (blueprint 9); mirrors Projector.RoomView on the server. */

export type Phase = "LOBBY" | "INTAKE" | "ROUND_VOTE" | "ANSWERING" | "VOTING" | "REVEAL" | "FINALE" | "CLOSED";
export type RoundKind = "ANSWER_DUEL" | "WHO_OF_US" | "TRUTH_OR_AI";
export type Role = "OWNER_SCREEN" | "CAPTAIN" | "PLAYER" | "SCREEN" | "AUDIENCE";
export type Tone = "FAMILY" | "CHEEKY" | "SPICY";
export type GameLength = "SHORT" | "LONG";
export type RoomMode = "STANDARD" | "STREAMER";
export type PauseReason = "OWNER" | "SCREEN_LOST" | "WAITING_FOR_PLAYERS";

export interface PlayerView {
  id: string;
  name: string;
  emoji: string;
  connected: boolean;
  score: number;
  captain: boolean;
  status: string;
  rank?: number;
}

export interface Assignment {
  duelId: string;
  prompt: string;
  answer?: string;
}

export interface You {
  role: Role;
  playerId?: string;
  name?: string;
  emoji?: string;
  score?: number;
  rank?: number;
  canVote?: boolean;
  voted?: boolean;
  myVote?: string;
  secretsLeft?: number;
  intakeNeeded?: boolean;
  assignments?: Assignment[];
  subject?: boolean;
  inDuel?: boolean;
  roundPoints?: number;
  title?: string;
}

export interface Option {
  id: string;
  text?: string;
  playerId?: string;
  votes?: number;
  audienceVotes?: number;
  authorId?: string;
  points?: number;
  winner?: boolean;
  correct?: boolean;
}

export interface RoundView {
  n: number;
  of: number;
  kind?: RoundKind;
  duelIndex?: number;
  duelCount?: number;
  prompt?: string;
  question?: string;
  statement?: string;
  subjectId?: string;
  options?: Option[];
  voters?: string[];
  eligible?: number;
  landslide?: boolean;
  lastOfRound?: boolean;
  points?: Record<string, number>;
  audienceTotal?: number;
  progress?: { playerId: string; answered: number; total: number }[];
}

export interface FinaleView {
  standings: PlayerView[];
  titles: Record<string, string>;
  winnerIds: string[];
  answerOfNight?: string;
  answerOfNightPrompt?: string;
  answerOfNightAuthorId?: string;
  speech?: string;
  ready: boolean;
}

export interface RoomView {
  version: number;
  serverTime: number;
  code?: string;
  phase: Phase;
  deadline?: number;
  paused?: { reason: PauseReason; welcomeBack: boolean; remainingMs?: number };
  waiting?: { deadline: number; missing: string[] };
  thinking: boolean;
  locked: boolean;
  settings: { tone: Tone; length: GameLength; mode: RoomMode; hideCode: boolean; language?: string };
  lobby?: { joinUrl?: string; audienceUrl?: string; audienceCount: number; minPlayers: number; maxPlayers: number };
  players?: PlayerView[];
  you: You;
  host?: { lineId: string; text: string; audioId?: string; skipped: boolean; aboutYou?: boolean; canSkip?: boolean };
  intake?: { questions: string[]; done: number; total: number };
  kindVote?: { voters: Record<RoundKind, string[]>; myKind?: RoundKind };
  round?: RoundView;
  finale?: FinaleView;
  secrets?: number;
  /** False when the host has no model to check secrets with: the game runs, but secrets are off. */
  secretsOpen?: boolean;
  starting: boolean;
  paywall?: string;
  sessionId?: string;
  audience?: { voted: boolean; total?: number };
}

export interface RoomStatus {
  code: string;
  phase: Phase;
  mode: RoomMode;
  players: number;
  maxPlayers: number;
  locked: boolean;
  full: boolean;
  joinable: boolean;
  audienceOpen: boolean;
  hideCode: boolean;
  /** Key of the viewers' link (/w/{key}) in streamer mode; the room code never works there. */
  audienceKey?: string | null;
  /** Room language ("en", "ru"): the language the host speaks and writes in. */
  language?: string;
}

export class GameError extends Error {
  readonly code: string;
  readonly details: Record<string, unknown>;

  constructor(code: string, message: string, details: Record<string, unknown> = {}) {
    super(message);
    this.name = "GameError";
    this.code = code;
    this.details = details;
  }
}
