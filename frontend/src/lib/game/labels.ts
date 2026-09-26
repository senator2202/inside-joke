import type { Company, GameLength, RoomMode, RoundKind, Tone } from "./types";

/**
 * Fixed order and icons of the game's choices. Their names and descriptions live in the dictionaries
 * (`kind.<KIND>.title`, `tone.<TONE>.title`, …) so they follow the interface language.
 */
export const KIND_ORDER: RoundKind[] = ["ANSWER_DUEL", "WHO_OF_US", "TRUTH_OR_AI"];
export const KIND_ICONS: Record<RoundKind, string> = { ANSWER_DUEL: "🥊", WHO_OF_US: "👉", TRUTH_OR_AI: "🤖" };
export const TONE_ORDER: Tone[] = ["FAMILY", "CHEEKY", "SPICY"];
export const LENGTH_ORDER: GameLength[] = ["SHORT", "LONG"];
export const MODE_ORDER: RoomMode[] = ["STANDARD", "STREAMER"];
export const COMPANY_ORDER: Company[] = ["FRIENDS", "COLLEAGUES", "FAMILY", "COUPLES", "ACQUAINTANCES"];
/** Groups that must stay clean: no Spicy tone for them (the server refuses it too). */
export const CLEAN_COMPANIES: ReadonlySet<Company> = new Set<Company>(["COLLEAGUES", "FAMILY"]);
/** The longest line the owner may write about the group. */
export const MAX_CONTEXT_CHARS = 120;
