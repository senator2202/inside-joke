/** Where to go after sign-in. Kept in sessionStorage because the Google and email flows leave the page. */
const KEY = "ij.returnTo";
const PENDING = "ij.loginPending";

export function safeReturnTo(value: string | null | undefined): string {
  if (!value || !value.startsWith("/") || value.startsWith("//") || value.startsWith("/\\")) return "/new";
  return value;
}

export function rememberReturnTo(value: string | null | undefined): void {
  window.sessionStorage.setItem(KEY, safeReturnTo(value));
  window.sessionStorage.setItem(PENDING, "1");
}

export function consumeReturnTo(): { path: string; startedHere: boolean } {
  const path = safeReturnTo(window.sessionStorage.getItem(KEY));
  const startedHere = window.sessionStorage.getItem(PENDING) === "1";
  window.sessionStorage.removeItem(KEY);
  window.sessionStorage.removeItem(PENDING);
  return { path, startedHere };
}
