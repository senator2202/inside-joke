/** Browser-side events, relayed by the server to PostHog (only an allow-listed few are accepted there). */
type ClientEvent = "landing_viewed" | "checkout_opened" | "guest_host_cta_clicked";

function anonymousId(): string {
  try {
    let id = localStorage.getItem("ij.anon");
    if (!id) {
      id = crypto.randomUUID().replaceAll("-", "");
      localStorage.setItem("ij.anon", id);
    }
    return id;
  } catch {
    return "anonymous";
  }
}

export function track(event: ClientEvent, properties: Record<string, string | number | boolean> = {}): void {
  void fetch("/api/events", {
    method: "POST",
    credentials: "same-origin",
    headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": readCsrf() },
    body: JSON.stringify({ event, anonymousId: anonymousId(), properties }),
    keepalive: true,
  }).catch(() => undefined);
}

function readCsrf(): string {
  const row = document.cookie.split("; ").find((r) => r.startsWith("XSRF-TOKEN="));
  return row ? decodeURIComponent(row.substring("XSRF-TOKEN=".length)) : "";
}

/** Landing link with a referral tag, used by "Host your own party" on guest screens. */
export function hostYourOwnUrl(source: string): string {
  return `/?ref=${encodeURIComponent(source)}`;
}
