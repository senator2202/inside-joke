import { useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router";
import { Button } from "../../components/Button";
import { Notice } from "../../components/Notice";
import { Spinner } from "../../components/Spinner";
import { PRODUCT_NAMES, type ProductId } from "../../lib/billing";
import {
  adminApi,
  money,
  passApi,
  type Page,
  type PassQuery,
  type PassRow,
  type PassSort,
  type PassSource,
  type PassStatus,
  type PassSummary,
} from "./adminApi";
import { errorText, formatDateTime, formatDay } from "./format";
import { Pagination, PAGE_SIZES } from "./Pagination";
import styles from "./Admin.module.css";

const STATUSES: PassStatus[] = ["ACTIVE", "UPCOMING", "EXPIRED", "REVOKED", "REFUNDED"];
const SORTS: PassSort[] = ["created", "ends", "amount", "email"];
const STATUS_LABELS: Record<PassStatus, string> = {
  ACTIVE: "Active",
  UPCOMING: "Upcoming",
  EXPIRED: "Expired",
  REVOKED: "Revoked",
  REFUNDED: "Refunded",
};
const REVOKE_LABELS: Record<NonNullable<PassRow["revokeReason"]>, string> = {
  REFUND: "refund",
  CHARGEBACK: "chargeback",
  ADMIN: "by an admin",
  ACCOUNT_DELETED: "account deleted",
};

/** Reads the pass log's state from the URL, ignoring anything invalid. */
export function readPassQuery(params: URLSearchParams): PassQuery {
  const pick = <T extends string>(key: string, allowed: readonly T[]): T | undefined => {
    const v = params.get(key)?.toUpperCase();
    return allowed.find((a) => a.toUpperCase() === v);
  };
  const day = (key: string) => (/^\d{4}-\d{2}-\d{2}$/.test(params.get(key) ?? "") ? params.get(key)! : undefined);
  const int = (key: string, fallback: number) => {
    const n = Number(params.get(key));
    return Number.isInteger(n) && n >= 0 ? n : fallback;
  };
  const size = int("size", 25);
  return {
    type: pick<ProductId>("type", ["PARTY_PASS", "HOST_PASS"]),
    source: pick<PassSource>("source", ["PURCHASE", "GRANT"]),
    status: pick<PassStatus>("status", STATUSES),
    from: day("from"),
    to: day("to"),
    email: params.get("email")?.trim() || undefined,
    sort: SORTS.find((s) => s === params.get("sort")) ?? "created",
    dir: params.get("dir") === "asc" ? "asc" : "desc",
    page: int("page", 0),
    size: PAGE_SIZES.includes(size) ? size : 25,
  };
}

export function PassesTab() {
  const [params, setParams] = useSearchParams();
  const query = useMemo(() => readPassQuery(params), [params]);
  const [reload, setReload] = useState(0);
  const listKey = JSON.stringify({ ...query, reload });
  const summaryKey = JSON.stringify({ type: query.type, source: query.source, from: query.from, to: query.to, email: query.email, reload });
  const [list, setList] = useState<{ key: string; page?: Page<PassRow>; error?: string } | null>(null);
  const [summary, setSummary] = useState<{ key: string; data?: PassSummary; error?: string } | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const emailTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    let cancelled = false;
    const q = JSON.parse(listKey) as PassQuery;
    passApi.list(q).then(
      (page) => !cancelled && setList({ key: listKey, page }),
      (e: unknown) => !cancelled && setList({ key: listKey, error: errorText(e) }),
    );
    return () => {
      cancelled = true;
    };
  }, [listKey]);

  useEffect(() => {
    let cancelled = false;
    const q = JSON.parse(summaryKey) as PassQuery;
    passApi.summary(q).then(
      (data) => !cancelled && setSummary({ key: summaryKey, data }),
      (e: unknown) => !cancelled && setSummary({ key: summaryKey, error: errorText(e) }),
    );
    return () => {
      cancelled = true;
    };
  }, [summaryKey]);

  useEffect(
    () => () => {
      if (emailTimer.current) clearTimeout(emailTimer.current);
    },
    [],
  );

  /** Writes changes to the URL; any filter change goes back to the first page. */
  const update = (patch: Partial<PassQuery>, keepPage = false) => {
    const next = { ...query, ...patch, page: keepPage ? (patch.page ?? query.page) : 0 };
    const out = new URLSearchParams(params);
    for (const key of ["type", "source", "status", "from", "to", "email", "sort", "dir", "page", "size"] as const) {
      const value = next[key];
      const isDefault =
        (key === "sort" && value === "created") ||
        (key === "dir" && value === "desc") ||
        (key === "page" && value === 0) ||
        (key === "size" && value === 25);
      if (value === undefined || value === "" || isDefault) out.delete(key);
      else out.set(key, String(value));
    }
    setParams(out);
  };

  const onEmail = (value: string) => {
    if (emailTimer.current) clearTimeout(emailTimer.current);
    emailTimer.current = setTimeout(() => update({ email: value.trim() || undefined }), 300);
  };

  const toggleSort = (sort: PassSort) => update({ sort, dir: query.sort === sort && query.dir === "desc" ? "asc" : "desc" });

  const revoke = async (row: PassRow) => {
    setActionError(null);
    try {
      await adminApi.revoke(row.id);
      setReload((n) => n + 1);
    } catch (e) {
      setActionError(errorText(e));
    }
  };

  const filtered = Boolean(query.type || query.source || query.status || query.from || query.to || query.email);
  const page = list?.key === listKey ? list.page : undefined;
  const loading = list?.key !== listKey;

  return (
    <div className={styles.stack}>
      <form className={styles.filters} role="search" aria-label="Filter passes" onSubmit={(e) => e.preventDefault()}>
        <label>
          Customer email
          <input
            key={query.email ?? ""}
            type="search"
            defaultValue={query.email ?? ""}
            placeholder="name or domain"
            onChange={(e) => onEmail(e.target.value)}
          />
        </label>
        <label>
          Pass
          <select value={query.type ?? ""} onChange={(e) => update({ type: (e.target.value || undefined) as ProductId | undefined })}>
            <option value="">All</option>
            <option value="PARTY_PASS">Party Pass</option>
            <option value="HOST_PASS">Host Pass</option>
          </select>
        </label>
        <label>
          Source
          <select value={query.source ?? ""} onChange={(e) => update({ source: (e.target.value || undefined) as PassSource | undefined })}>
            <option value="">All</option>
            <option value="PURCHASE">Bought</option>
            <option value="GRANT">Granted</option>
          </select>
        </label>
        <label>
          Status
          <select value={query.status ?? ""} onChange={(e) => update({ status: (e.target.value || undefined) as PassStatus | undefined })}>
            <option value="">All</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {STATUS_LABELS[s]}
              </option>
            ))}
          </select>
        </label>
        <label>
          From
          <input type="date" value={query.from ?? ""} max={query.to} onChange={(e) => update({ from: e.target.value || undefined })} />
        </label>
        <label>
          To
          <input type="date" value={query.to ?? ""} min={query.from} onChange={(e) => update({ to: e.target.value || undefined })} />
        </label>
        {filtered && (
          <Button variant="quiet" type="button" onClick={() => setParams(new URLSearchParams(params.get("tab") ? { tab: "passes" } : {}))}>
            Clear filters
          </Button>
        )}
      </form>

      <SummaryCards state={summary?.key === summaryKey ? summary : null} />

      {actionError && <Notice tone="error">{actionError}</Notice>}
      {list?.key === listKey && list.error && <Notice tone="error">{list.error}</Notice>}

      <div className={styles.tableWrap} aria-busy={loading}>
        <table className={styles.table}>
          <caption className="visually-hidden">Passes</caption>
          <thead>
            <tr>
              <SortHeader label="Sold / granted" column="created" query={query} onSort={toggleSort} />
              <SortHeader label="Customer" column="email" query={query} onSort={toggleSort} />
              <th scope="col">Pass</th>
              <th scope="col">Source</th>
              <SortHeader label="Paid" column="amount" query={query} onSort={toggleSort} numeric />
              <th scope="col">Status</th>
              <SortHeader label="Valid until" column="ends" query={query} onSort={toggleSort} />
              <th scope="col" className={styles.num}>
                Games
              </th>
              <th scope="col">
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {page?.items.map((row) => (
              <tr key={row.id}>
                <td>{formatDateTime(row.createdAt)}</td>
                <td className={styles.email}>
                  {row.userEmail}
                  {row.userDeleted && <span className={styles.tag}>deleted</span>}
                </td>
                <td>
                  {PRODUCT_NAMES[row.type]}
                  {row.monthlyGameLimit !== null && <span className={styles.muted}> · {row.monthlyGameLimit}/month</span>}
                </td>
                <td>
                  {row.source === "GRANT" ? (
                    <>Granted{row.grantedByEmail && <span className={styles.muted}> by {row.grantedByEmail}</span>}</>
                  ) : (
                    <span title={row.txnId ?? undefined}>Bought</span>
                  )}
                </td>
                <td className={styles.num}>{row.amountMinor !== null && row.currency ? money(row.amountMinor, row.currency) : "—"}</td>
                <td>
                  <StatusCell row={row} />
                </td>
                <td>
                  {formatDay(row.startsAt)} – {formatDay(row.endsAt)}
                </td>
                <td className={styles.num}>{row.gamesPlayed}</td>
                <td>
                  {(row.status === "ACTIVE" || row.status === "UPCOMING") && (
                    <Button
                      variant="quiet"
                      onClick={() => void revoke(row)}
                      aria-label={`Revoke ${PRODUCT_NAMES[row.type]} of ${row.userEmail}`}
                    >
                      Revoke
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {loading && (
          <div className={styles.center}>
            <Spinner label="Loading passes" />
          </div>
        )}
        {page && page.items.length === 0 && (
          <p className={styles.empty}>{filtered ? "No passes match these filters." : "No passes yet."}</p>
        )}
      </div>

      {page && page.totalItems > 0 && (
        <Pagination page={page} onPage={(p) => update({ page: p }, true)} onSize={(size) => update({ size })} />
      )}
    </div>
  );
}

function SortHeader({
  label,
  column,
  query,
  onSort,
  numeric,
}: {
  label: string;
  column: PassSort;
  query: PassQuery;
  onSort: (c: PassSort) => void;
  numeric?: boolean;
}) {
  const active = query.sort === column;
  return (
    <th
      scope="col"
      aria-sort={active ? (query.dir === "asc" ? "ascending" : "descending") : "none"}
      className={numeric ? styles.num : undefined}
    >
      <button type="button" className={styles.sortButton} onClick={() => onSort(column)}>
        {label} <span aria-hidden="true">{active ? (query.dir === "asc" ? "▲" : "▼") : "↕"}</span>
      </button>
    </th>
  );
}

function StatusCell({ row }: { row: PassRow }) {
  const detail =
    row.status === "REFUNDED"
      ? `${row.refundKind === "CHARGEBACK" ? "Chargeback" : "Refund"} ${formatDateTime(row.refundedAt)}`
      : row.status === "REVOKED"
        ? `${formatDateTime(row.revokedAt)}${row.revokeReason ? `, ${REVOKE_LABELS[row.revokeReason]}` : ""}` +
          `${row.revokedByEmail ? ` (${row.revokedByEmail})` : ""}`
        : null;
  return (
    <>
      <span className={styles.status} data-status={row.status}>
        {row.status === "REFUNDED" && row.refundKind === "CHARGEBACK" ? "Chargeback" : STATUS_LABELS[row.status]}
      </span>
      {detail && <span className={styles.statusDetail}>{detail}</span>}
    </>
  );
}

function SummaryCards({ state }: { state: { data?: PassSummary; error?: string } | null }) {
  if (!state)
    return (
      <div className={styles.kpis} aria-busy="true">
        <div className={styles.kpi}>
          <Spinner label="Loading totals" />
        </div>
      </div>
    );
  if (state.error || !state.data) return <Notice tone="error">{state.error ?? "Couldn't load the totals."}</Notice>;
  const s = state.data;
  const party = s.byType.PARTY_PASS;
  const host = s.byType.HOST_PASS;
  return (
    <div className={styles.kpis}>
      <div className={styles.kpi}>
        <p className={styles.kpiLabel}>Sold</p>
        <p className={styles.kpiValue}>{s.sold}</p>
        <p className={styles.kpiDetail}>
          Party {party?.sold ?? 0} · Host {host?.sold ?? 0}
        </p>
      </div>
      <div className={styles.kpi}>
        <p className={styles.kpiLabel}>Granted</p>
        <p className={styles.kpiValue}>{s.granted}</p>
        <p className={styles.kpiDetail}>
          Party {party?.granted ?? 0} · Host {host?.granted ?? 0}
        </p>
      </div>
      <div className={styles.kpi} data-warn={(s.sold > 0 && s.refundRate > 0.1) || undefined}>
        <p className={styles.kpiLabel}>Refunded</p>
        <p className={styles.kpiValue}>{s.refunded + s.chargebacks}</p>
        <p className={styles.kpiDetail}>
          {s.refunded} refunds · {s.chargebacks} chargebacks · {(s.refundRate * 100).toFixed(1)}% of sold
        </p>
      </div>
      <div className={styles.kpi}>
        <p className={styles.kpiLabel}>Active now</p>
        <p className={styles.kpiValue}>{s.activeNow}</p>
      </div>
      <div className={`${styles.kpi} ${styles.kpiWide}`}>
        <p className={styles.kpiLabel}>Revenue</p>
        {s.revenue.length === 0 ? (
          <p className={styles.kpiValue}>—</p>
        ) : (
          <table className={styles.miniTable}>
            <thead>
              <tr>
                <th scope="col">Currency</th>
                <th scope="col">Gross</th>
                <th scope="col">Refunded</th>
                <th scope="col">Net</th>
              </tr>
            </thead>
            <tbody>
              {s.revenue.map((r) => (
                <tr key={r.currency}>
                  <td>{r.currency}</td>
                  <td className={styles.num}>{money(r.grossMinor, r.currency)}</td>
                  <td className={styles.num}>{money(r.refundedMinor, r.currency)}</td>
                  <td className={styles.num}>
                    <strong>{money(r.netMinor, r.currency)}</strong>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
