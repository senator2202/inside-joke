import type { GameLength, RoomMode, RoundKind, Tone } from "./types";

/**
 * Fixed order and icons of the game's choices. Their names and descriptions live in the dictionaries
 * (`kind.<KIND>.title`, `tone.<TONE>.title`, …) so they follow the interface language.
 */
export const KIND_ORDER: RoundKind[] = ["ANSWER_DUEL", "WHO_OF_US", "TRUTH_OR_AI"];
export const KIND_ICONS: Record<RoundKind, string> = { ANSWER_DUEL: "🥊", WHO_OF_US: "👉", TRUTH_OR_AI: "🤖" };
export const TONE_ORDER: Tone[] = ["FAMILY", "CHEEKY", "SPICY"];
export const LENGTH_ORDER: GameLength[] = ["SHORT", "LONG"];
export const MODE_ORDER: RoomMode[] = ["STANDARD", "STREAMER"];
