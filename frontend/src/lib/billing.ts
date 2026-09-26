import { api } from "./api";
import { translate, type Lang } from "./i18n";

export type ProductId = "PARTY_PASS" | "HOST_PASS";

export interface Pass {
  id: string;
  type: ProductId;
  startsAt: string;
  endsAt: string;
  monthlyGameLimit: number | null;
  gamesLeftThisMonth: number | null;
  grantedByAdmin?: boolean;
}

export interface AccessStatus {
  access: {
    freeGamesEnabled: boolean;
    freeGameAvailable: boolean;
    nextFreeGameAt: string | null;
    /** Passes running now: these give access. */
    passes: Pass[];
    /** Passes bought ahead, soonest first: each starts when the previous one of its kind ends. */
    upcomingPasses: Pass[];
    nextGame: "FREE" | ProductId | "PAYWALL";
    paywallReason: string | null;
  };
  drainMode: boolean;
}

export interface Offer {
  product: ProductId;
  priceId: string;
  listPriceUsdCents: number;
  monthlyGameLimit: number | null;
  validityHours: number;
}

export interface CheckoutConfig {
  available: boolean;
  environment: string;
  clientToken: string | null;
  offers: Offer[];
  email: string;
  customData: Record<string, string>;
  supportEmail: string;
}

export const PRODUCT_NAMES: Record<ProductId, string> = { PARTY_PASS: "Party Pass", HOST_PASS: "Host Pass" };

export function fetchAccess(signal?: AbortSignal): Promise<AccessStatus> {
  return api<AccessStatus>("/api/billing/passes", { signal });
}

export function fetchCheckout(signal?: AbortSignal): Promise<CheckoutConfig> {
  return api<CheckoutConfig>("/api/billing/checkout", { signal });
}

/** "today, 21:40", "tomorrow, 21:40" or "23 Sep 2027" (in the interface language); for a pass's start as well as its end. */
export function formatPassEnd(iso: string, now: Date = new Date(), lang: Lang = "en"): string {
  const locale = lang === "ru" ? "ru-RU" : "en-GB";
  const end = new Date(iso);
  const time = end.toLocaleTimeString(locale, { hour: "2-digit", minute: "2-digit" });
  const day = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  const diffDays = Math.round((day(end) - day(now)) / 86_400_000);
  if (diffDays === 0) return translate(lang, "date.today", { time });
  if (diffDays === 1) return translate(lang, "date.tomorrow", { time });
  return end.toLocaleDateString(locale, { day: "numeric", month: "short", year: "numeric" });
}

export function listPrice(cents: number): string {
  return `$${(cents / 100).toFixed(2)}`;
}
