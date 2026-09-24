import { isApiError } from "../../lib/api";

/** Shared formatting for the admin panel (English, like the rest of the admin UI). */

export function errorText(e: unknown): string {
  return isApiError(e) ? e.message : "Couldn't load. Check the connection and try again.";
}

/** "23 Sep 2026, 10:05" — or with seconds, for logs where order within a minute matters. "—" when missing. */
export function formatDateTime(iso: string | null, { seconds = false }: { seconds?: boolean } = {}): string {
  return iso ? new Date(iso).toLocaleString("en-GB", { dateStyle: "medium", timeStyle: seconds ? "medium" : "short" }) : "—";
}

/** "23 Sep 2026". */
export function formatDay(iso: string): string {
  return new Date(iso).toLocaleDateString("en-GB", { day: "numeric", month: "short", year: "numeric" });
}
