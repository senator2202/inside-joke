import { useCallback, useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router";
import { Button } from "../../components/Button";
import { Notice } from "../../components/Notice";
import { Spinner } from "../../components/Spinner";
import {
  aiApi,
  type AiCallQuery,
  type AiCallRow,
  type AiCallSummary,
  type AiOutcome,
  type AiPurpose,
  type AiStatus,
  type AiWindow,
  type Page,
} from "./adminApi";
import { errorText, formatDateTime } from "./format";
import { Pagination, PAGE_SIZES } from "./Pagination";
import styles from "./Admin.module.css";

export const PURPOSE_LABELS: Record<AiPurpose, string> = {
  ROUND_GEN: "Round generation",
  HOST_LINE: "Host lines",
  FINALE: "Finale",
  MODERATION: "Moderation",
  TTS: "Voice",
};
export const OUTCOME_LABELS: Record<AiOutcome, string> = {
  OK: "OK",
  ERROR: "Error",
  TIMEOUT: "Timeout",
  INVALID_JSON: "Invalid output",
  FALLBACK: "Not called (budget)",
};
const PURPOSES = Object.keys(PURPOSE_LABELS) as AiPurpose[];
const OUTCOMES = Object.keys(OUTCOME_LABELS) as AiOutcome[];
const STATUS_REFRESH_MS = 30_000;

/** Micro-dollars to "$0.0012": AI calls cost fractions of a cent. */
export function usd(micros: number): string {
  return `$${(micros / 1_000_000).toFixed(micros > 0 && micros < 10_000 ? 4 : 2)}`;
}

function failed(outcome: AiOutcome): boolean {
  return outcome === "ERROR" || outcome === "TIMEOUT" || outcome === "INVALID_JSON";
}

export function readAiQuery(params: URLSearchParams): AiCallQuery {
  const day = (key: string) => (/^\d{4}-\d{2}-\d{2}$/.test(params.get(key) ?? "") ? params.get(key)! : undefined);
  const purpose = PURPOSES.find((p) => p === params.get("purpose")?.toUpperCase());
  const rawOutcome = params.get("outcome")?.toUpperCase();
  const outcome = rawOutcome === "FAILURES" ? "FAILURES" : OUTCOMES.find((o) => o === rawOutcome);
  const page = Number(params.get("page"));
  const size = Number(params.get("size"));
  return {
    from: day("from"),
    to: day("to"),
    purpose,
    outcome,
    page: Number.isInteger(page) && page >= 0 ? page : 0,
    size: PAGE_SIZES.includes(size) ? size : 25,
  };
}

export function AiTab() {
  return (
    <div className={styles.stack}>
      <StatusCard />
      <CallLog />
    </div>
  );
}

// ------------------------------------------------------------------ status

function StatusCard() {
  const [status, setStatus] = useState<AiStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loadedAt, setLoadedAt] = useState<Date | null>(null);

  const load = useCallback(() => {
    aiApi.status().then(
      (s) => {
        setStatus(s);
        setError(null);
        setLoadedAt(new Date());
      },
      (e: unknown) => setError(errorText(e)),
    );
  }, []);

  useEffect(() => {
    load();
    const timer = setInterval(load, STATUS_REFRESH_MS);
    return () => clearInterval(timer);
  }, [load]);

  const failingNow = status?.lastFailure && (!status.lastCall || status.lastFailure.at >= status.lastCall.at);

  return (
    <section className={styles.card} aria-labelledby="ai-status-title">
      <div className={styles.titleRow}>
        <h2 id="ai-status-title">Status</h2>
        <Button variant="quiet" onClick={load}>
          Refresh
        </Button>
        {loadedAt && <span className={styles.muted}>updated {loadedAt.toLocaleTimeString("en-GB")}</span>}
      </div>
      {error && <Notice tone="error">{error}</Notice>}
      {!status && !error && <Spinner label="Loading AI status" />}
      {status && (
        <>
          <dl className={styles.facts}>
            <div>
              <dt>API key</dt>
              <dd>
                {status.keyConfigured ? (
                  <span className={styles.status} data-status="ACTIVE">
                    Set
                  </span>
                ) : (
                  <span className={styles.status} data-status="REVOKED">
                    Not set
                  </span>
                )}
              </dd>
            </div>
            <div>
              <dt>Model</dt>
              <dd>
                <code>{status.model}</code>
              </dd>
            </div>
            <div>
              <dt>Last call</dt>
              <dd>{status.lastCall ? <CallLine call={status.lastCall} /> : "No calls yet"}</dd>
            </div>
            <div>
              <dt>Last failure</dt>
              <dd>{status.lastFailure ? <CallLine call={status.lastFailure} /> : "None"}</dd>
            </div>
          </dl>
          {!status.keyConfigured && (
            <Notice tone="error" title="ANTHROPIC_API_KEY is not set">
              The host plays on prewritten content. Add the key to the environment and restart the server.
            </Notice>
          )}
          {status.keyConfigured && failingNow && (
            <Notice tone="error" title="The latest call to Anthropic failed">
              {status.lastFailure!.error ?? status.lastFailure!.outcome}
            </Notice>
          )}
          <RateLimits limits={status.rateLimits} />
        </>
      )}
    </section>
  );
}

function CallLine({ call }: { call: AiCallSummary }) {
  return (
    <>
      <span className={styles.status} data-status={failed(call.outcome) ? "REVOKED" : "ACTIVE"}>
        {OUTCOME_LABELS[call.outcome]}
      </span>{" "}
      {PURPOSE_LABELS[call.purpose]} · {formatDateTime(call.at, { seconds: true })} · {call.latencyMs} ms
      {call.error && <span className={styles.statusDetail}>{call.error}</span>}
    </>
  );
}

function RateLimits({ limits }: { limits: AiStatus["rateLimits"] }) {
  if (!limits) {
    return <p className={styles.muted}>Rate limits appear after the first call to Anthropic since the server started.</p>;
  }
  const rows: [string, AiWindow | null][] = [
    ["Requests", limits.requests],
    ["Tokens", limits.tokens],
    ["Input tokens", limits.inputTokens],
    ["Output tokens", limits.outputTokens],
  ];
  return (
    <div>
      <h3 className={styles.subTitle}>Rate limits</h3>
      <p className={styles.muted}>
        From Anthropic&rsquo;s latest response ({formatDateTime(limits.capturedAt, { seconds: true })}, HTTP {limits.httpStatus}).
        {limits.retryAfter && <> Retry after {limits.retryAfter} s.</>}
      </p>
      <ul className={styles.meters}>
        {rows
          .filter(([, w]) => w)
          .map(([label, w]) => {
            const share = w!.limit ? Math.max(0, Math.min(1, (w!.remaining ?? 0) / w!.limit)) : null;
            return (
              <li key={label}>
                <span>{label}</span>
                {share !== null ? (
                  <meter
                    min={0}
                    max={w!.limit!}
                    low={w!.limit! * 0.2}
                    optimum={w!.limit!}
                    value={w!.remaining ?? 0}
                    aria-label={`${label}: ${w!.remaining ?? "?"} of ${w!.limit} left`}
                  />
                ) : (
                  <span />
                )}
                <span className={styles.num}>
                  {w!.remaining?.toLocaleString("en-GB") ?? "?"} / {w!.limit?.toLocaleString("en-GB") ?? "?"} left
                </span>
                <span className={styles.muted}>{w!.reset ? `resets ${formatDateTime(w!.reset, { seconds: true })}` : ""}</span>
              </li>
            );
          })}
      </ul>
    </div>
  );
}

// ------------------------------------------------------------------ call log

function CallLog() {
  const [params, setParams] = useSearchParams();
  const query = useMemo(() => readAiQuery(params), [params]);
  const key = JSON.stringify(query);
  const [state, setState] = useState<{ key: string; page?: Page<AiCallRow>; error?: string } | null>(null);

  useEffect(() => {
    let cancelled = false;
    aiApi.calls(JSON.parse(key) as AiCallQuery).then(
      (page) => !cancelled && setState({ key, page }),
      (e: unknown) => !cancelled && setState({ key, error: errorText(e) }),
    );
    return () => {
      cancelled = true;
    };
  }, [key]);

  const update = (patch: Partial<AiCallQuery>, keepPage = false) => {
    const next = { ...query, ...patch, page: keepPage ? (patch.page ?? query.page) : 0 };
    const out = new URLSearchParams(params);
    for (const k of ["from", "to", "purpose", "outcome", "page", "size"] as const) {
      const v = next[k];
      if (v === undefined || v === "" || (k === "page" && v === 0) || (k === "size" && v === 25)) out.delete(k);
      else out.set(k, String(v));
    }
    setParams(out);
  };

  const filtered = Boolean(query.from || query.to || query.purpose || query.outcome);
  const page = state?.key === key ? state.page : undefined;
  return (
    <section className={styles.card} aria-labelledby="ai-calls-title">
      <h2 id="ai-calls-title">Call log</h2>
      <form className={styles.filters} role="search" aria-label="Filter AI calls" onSubmit={(e) => e.preventDefault()}>
        <label>
          From
          <input type="date" value={query.from ?? ""} max={query.to} onChange={(e) => update({ from: e.target.value || undefined })} />
        </label>
        <label>
          To
          <input type="date" value={query.to ?? ""} min={query.from} onChange={(e) => update({ to: e.target.value || undefined })} />
        </label>
        <label>
          Purpose
          <select value={query.purpose ?? ""} onChange={(e) => update({ purpose: (e.target.value || undefined) as AiPurpose | undefined })}>
            <option value="">All</option>
            {PURPOSES.map((p) => (
              <option key={p} value={p}>
                {PURPOSE_LABELS[p]}
              </option>
            ))}
          </select>
        </label>
        <label>
          Outcome
          <select
            value={query.outcome ?? ""}
            onChange={(e) => update({ outcome: (e.target.value || undefined) as AiCallQuery["outcome"] })}
          >
            <option value="">All</option>
            <option value="FAILURES">Failures only</option>
            {OUTCOMES.map((o) => (
              <option key={o} value={o}>
                {OUTCOME_LABELS[o]}
              </option>
            ))}
          </select>
        </label>
        {filtered && (
          <Button variant="quiet" type="button" onClick={() => setParams(new URLSearchParams({ tab: "ai" }))}>
            Clear filters
          </Button>
        )}
      </form>

      {state?.key === key && state.error && <Notice tone="error">{state.error}</Notice>}
      <div className={styles.tableWrap} aria-busy={state?.key !== key}>
        <table className={styles.table}>
          <caption className="visually-hidden">AI calls</caption>
          <thead>
            <tr>
              <th scope="col">Time</th>
              <th scope="col">Purpose</th>
              <th scope="col">Outcome</th>
              <th scope="col">Model</th>
              <th scope="col" className={styles.num}>
                Tokens in / out
              </th>
              <th scope="col" className={styles.num}>
                Cost
              </th>
              <th scope="col" className={styles.num}>
                Latency
              </th>
              <th scope="col">Game</th>
              <th scope="col">Error</th>
            </tr>
          </thead>
          <tbody>
            {page?.items.map((c) => (
              <tr key={c.id}>
                <td>{formatDateTime(c.createdAt, { seconds: true })}</td>
                <td>
                  {PURPOSE_LABELS[c.purpose]}
                  {c.freeGame && <span className={styles.tag}>free</span>}
                </td>
                <td>
                  <span className={styles.status} data-status={failed(c.outcome) ? "REVOKED" : c.outcome === "OK" ? "ACTIVE" : "EXPIRED"}>
                    {OUTCOME_LABELS[c.outcome]}
                  </span>
                </td>
                <td>
                  <code>{c.model}</code>
                  {c.promptVersion && <span className={styles.statusDetail}>{c.promptVersion}</span>}
                </td>
                <td className={styles.num}>
                  {c.purpose === "TTS"
                    ? `${c.ttsChars ?? 0} chars`
                    : c.inputTokens === null
                      ? "—"
                      : `${c.inputTokens.toLocaleString("en-GB")} / ${(c.outputTokens ?? 0).toLocaleString("en-GB")}`}
                </td>
                <td className={styles.num}>{usd(c.costMicros)}</td>
                <td className={styles.num}>{c.latencyMs} ms</td>
                <td>{c.roomCode ?? "—"}</td>
                <td className={styles.errorCell}>{c.error ?? ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {state?.key !== key && (
          <div className={styles.center}>
            <Spinner label="Loading AI calls" />
          </div>
        )}
        {page && page.items.length === 0 && (
          <p className={styles.empty}>{filtered ? "No calls match these filters." : "No AI calls yet."}</p>
        )}
      </div>
      {page && page.totalItems > 0 && (
        <Pagination page={page} onPage={(p) => update({ page: p }, true)} onSize={(size) => update({ size })} />
      )}
    </section>
  );
}
