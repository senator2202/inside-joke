import { api } from "../../lib/api";
import type { Pass, ProductId } from "../../lib/billing";

export interface Live {
  rooms: number;
  inGame: number;
  players: number;
  viewers: number;
}

export interface Metrics {
  from: string;
  to: string;
  games: { total: number; free: number; paid: number; completed: number; avgPlayers: number; endReasons: Record<string, number> };
  money: { revenue: { currency: string; amountMinor: number }[]; purchases: number; byProduct: Record<string, number>; refunds: number };
  ai: {
    costMicros: number;
    freeGameCostMicros: number;
    costPerGameMicros: number;
    calls: number;
    failures: number;
    costByPurpose: Record<string, number>;
  };
  funnel: { newHosts: number; activeHosts: number; payingHosts: number; conversion: number };
  live: Live;
  days: { date: string; games: number; paidGames: number; aiCostMicros: number }[];
}

export interface AdminUser {
  id: string;
  email: string;
  displayName: string | null;
  role: string;
  googleLinked: boolean;
  createdAt: string;
  lastLoginAt: string | null;
  games: number;
  passes: Pass[];
}

export type Settings = {
  free_games_enabled: boolean;
  tts_enabled: boolean;
  daily_free_ai_budget_micros: number;
  audience_cap: number;
  drain_mode: boolean;
};

export interface FailedWebhook {
  provider: string;
  eventId: string;
  eventType: string;
  receivedAt: string;
  lastError: string | null;
}

export const adminApi = {
  metrics: (from: string, to: string) => api<Metrics>(`/api/admin/metrics?from=${from}&to=${to}`),
  users: (email: string) => api<AdminUser[]>(`/api/admin/users?email=${encodeURIComponent(email)}`),
  grant: (userId: string, type: ProductId, days: number) =>
    api<AdminUser>(`/api/admin/users/${userId}/passes`, { method: "POST", body: { type, days } }),
  revoke: (passId: string) => api<{ revoked: boolean }>(`/api/admin/passes/${passId}/revoke`, { method: "POST", body: {} }),
  settings: () => api<Settings>("/api/admin/settings"),
  updateSetting: (key: keyof Settings, value: boolean | number) =>
    api<Settings>(`/api/admin/settings/${key}`, { method: "PUT", body: { value } }),
  drain: (enabled: boolean) => api<{ drainMode: boolean; live: Live }>("/api/admin/drain", { method: "POST", body: { enabled } }),
  failedWebhooks: () => api<FailedWebhook[]>("/api/admin/webhooks/failed"),
  replay: (eventId: string) =>
    api<{ processed: boolean; result: string }>(`/api/admin/webhooks/${encodeURIComponent(eventId)}/replay`, { method: "POST", body: {} }),
};

/** Minor units → formatted amount, respecting currencies without cents (JPY, KRW…). */
export function money(amountMinor: number, currency: string): string {
  const fmt = new Intl.NumberFormat("en", { style: "currency", currency });
  const digits = fmt.resolvedOptions().maximumFractionDigits ?? 2;
  return fmt.format(amountMinor / 10 ** digits);
}

export function usdFromMicros(micros: number): string {
  return `$${(micros / 1_000_000).toFixed(micros > 0 && micros < 10_000 ? 4 : 2)}`;
}

export function isoDay(d: Date): string {
  return d.toISOString().slice(0, 10);
}

// ------------------------------------------------------------------ pass log

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export type PassStatus = "ACTIVE" | "UPCOMING" | "EXPIRED" | "REVOKED" | "REFUNDED";
export type PassSource = "PURCHASE" | "GRANT";
export type PassSort = "created" | "ends" | "amount" | "email";

export interface PassRow {
  id: string;
  type: ProductId;
  source: PassSource;
  status: PassStatus;
  userId: string;
  userEmail: string;
  userDeleted: boolean;
  createdAt: string;
  startsAt: string;
  endsAt: string;
  monthlyGameLimit: number | null;
  gamesPlayed: number;
  txnId: string | null;
  amountMinor: number | null;
  currency: string | null;
  refundedAt: string | null;
  refundKind: "REFUND" | "CHARGEBACK" | null;
  grantedByEmail: string | null;
  revokedAt: string | null;
  revokeReason: "REFUND" | "CHARGEBACK" | "ADMIN" | "ACCOUNT_DELETED" | null;
  revokedByEmail: string | null;
}

export interface PassSummary {
  sold: number;
  granted: number;
  refunded: number;
  chargebacks: number;
  refundRate: number;
  activeNow: number;
  revenue: { currency: string; grossMinor: number; refundedMinor: number; netMinor: number }[];
  byType: Partial<Record<ProductId, { sold: number; granted: number; refunded: number; chargebacks: number }>>;
}

/** Filters, sort and page of the pass log, exactly as they appear in the URL. */
export interface PassQuery {
  type?: ProductId;
  source?: PassSource;
  status?: PassStatus;
  from?: string;
  to?: string;
  email?: string;
  sort: PassSort;
  dir: "asc" | "desc";
  page: number;
  size: number;
}

export interface NotAppliedRow {
  eventId: string;
  eventType: string;
  receivedAt: string;
  action: string | null;
  status: string | null;
  txnId: string | null;
  reason: string | null;
  detail: string | null;
  purchaseId: string | null;
  product: ProductId | null;
  amountMinor: number | null;
  currency: string | null;
  purchaseStatus: string | null;
  userId: string | null;
  userEmail: string | null;
  passId: string | null;
  passRevokedAt: string | null;
  passEndsAt: string | null;
  passHeld: boolean;
}

function queryString(params: Record<string, string | number | undefined>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== "") q.set(k, String(v));
  }
  return q.toString();
}

export const passApi = {
  list: (q: PassQuery) => api<Page<PassRow>>(`/api/admin/passes?${queryString({ ...q })}`),
  summary: (q: PassQuery) =>
    api<PassSummary>(
      `/api/admin/passes/summary?${queryString({ type: q.type, source: q.source, from: q.from, to: q.to, email: q.email })}`,
    ),
  notApplied: (stillHeld: boolean, page: number, size: number) =>
    api<Page<NotAppliedRow>>(`/api/admin/webhooks/not-applied?${queryString({ stillHeld: String(stillHeld), page, size })}`),
};

// ------------------------------------------------------------------ AI

export type AiPurpose = "ROUND_GEN" | "HOST_LINE" | "FINALE" | "MODERATION" | "TTS";
export type AiOutcome = "OK" | "TIMEOUT" | "ERROR" | "INVALID_JSON" | "FALLBACK";

export interface AiWindow {
  limit: number | null;
  remaining: number | null;
  reset: string | null;
}

export interface AiCallSummary {
  at: string;
  purpose: AiPurpose;
  model: string;
  outcome: AiOutcome;
  latencyMs: number;
  error: string | null;
}

export interface AiStatus {
  keyConfigured: boolean;
  model: string;
  lastCall: AiCallSummary | null;
  lastFailure: AiCallSummary | null;
  rateLimits: {
    capturedAt: string;
    httpStatus: number;
    requests: AiWindow | null;
    tokens: AiWindow | null;
    inputTokens: AiWindow | null;
    outputTokens: AiWindow | null;
    retryAfter: string | null;
  } | null;
}

export interface AiCallRow {
  id: number;
  createdAt: string;
  purpose: AiPurpose;
  provider: string;
  model: string;
  promptVersion: string | null;
  outcome: AiOutcome;
  inputTokens: number | null;
  outputTokens: number | null;
  ttsChars: number | null;
  costMicros: number;
  latencyMs: number;
  freeGame: boolean;
  gameSessionId: string | null;
  roomCode: string | null;
  error: string | null;
}

export interface AiCallQuery {
  from?: string;
  to?: string;
  purpose?: AiPurpose;
  outcome?: AiOutcome | "FAILURES";
  page: number;
  size: number;
}

export const aiApi = {
  status: () => api<AiStatus>("/api/admin/ai/status"),
  calls: (q: AiCallQuery) => api<Page<AiCallRow>>(`/api/admin/ai/calls?${queryString({ ...q })}`),
};
