import { useCallback, useEffect, useState } from "react";
import { useSearchParams } from "react-router";
import { Button } from "../../components/Button";
import { Notice } from "../../components/Notice";
import { Spinner } from "../../components/Spinner";
import { PRODUCT_NAMES } from "../../lib/billing";
import { adminApi, money, passApi, type FailedWebhook, type NotAppliedRow, type Page } from "./adminApi";
import { errorText, formatDateTime } from "./format";
import { Pagination } from "./Pagination";
import styles from "./Admin.module.css";

/** Plain-language names for the reasons the server records when it doesn't apply a refund or chargeback. */
export const REASON_LABELS: Record<string, string> = {
  AWAITING_APPROVAL: "Waiting for Paddle's approval",
  REJECTED: "Rejected by Paddle",
  PARTIAL: "Partial refund",
  CHARGEBACK_WARNING: "Chargeback warning",
  CHARGEBACK_REVERSED: "Chargeback reversed",
  UNEXPECTED_STATUS: "Unexpected status",
};

export function PaymentIssues() {
  return (
    <div className={styles.stack}>
      <FailedEvents />
      <NotApplied />
    </div>
  );
}

function FailedEvents() {
  const [events, setEvents] = useState<FailedWebhook[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<Record<string, string>>({});
  const load = useCallback(() => {
    adminApi.failedWebhooks().then(setEvents, (e: unknown) => setError(errorText(e)));
  }, []);
  useEffect(load, [load]);

  const replay = async (id: string) => {
    try {
      const r = await adminApi.replay(id);
      setOutcome((o) => ({
        ...o,
        [id]: r.processed ? `Processed: ${r.result.toLowerCase().replaceAll("_", " ")}` : `Still failing: ${r.result}`,
      }));
      if (r.processed) load();
    } catch (e) {
      setOutcome((o) => ({ ...o, [id]: errorText(e) }));
    }
  };

  return (
    <section className={styles.card} aria-labelledby="failed-title">
      <h2 id="failed-title">Events that failed</h2>
      <p className={styles.muted}>
        Paddle events that arrived but couldn&rsquo;t be applied (unknown price, unknown account). Fix the cause, then replay.
      </p>
      {error && <Notice tone="error">{error}</Notice>}
      {!events && !error && <Spinner label="Loading" />}
      {events?.length === 0 && <p>Nothing stuck. 🎉</p>}
      {events && events.length > 0 && (
        <div className={styles.tableWrap}>
          <table className={styles.table}>
            <thead>
              <tr>
                <th scope="col">Received</th>
                <th scope="col">Event</th>
                <th scope="col">Problem</th>
                <th scope="col" />
              </tr>
            </thead>
            <tbody>
              {events.map((e) => (
                <tr key={e.eventId}>
                  <td>{formatDateTime(e.receivedAt)}</td>
                  <td>
                    <code>{e.eventType}</code>
                    <br />
                    <code className={styles.muted}>{e.eventId}</code>
                  </td>
                  <td>{outcome[e.eventId] ?? e.lastError ?? "Not processed yet"}</td>
                  <td>
                    <Button variant="secondary" onClick={() => void replay(e.eventId)}>
                      Replay
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

function NotApplied() {
  const [params, setParams] = useSearchParams();
  const stillHeld = params.get("held") !== "all";
  const pageNumber = Math.max(0, Number(params.get("npage")) || 0);
  const [reload, setReload] = useState(0);
  const key = JSON.stringify({ stillHeld, pageNumber, reload });
  const [state, setState] = useState<{ key: string; page?: Page<NotAppliedRow>; error?: string } | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const q = JSON.parse(key) as { stillHeld: boolean; pageNumber: number };
    passApi.notApplied(q.stillHeld, q.pageNumber, 25).then(
      (page) => !cancelled && setState({ key, page }),
      (e: unknown) => !cancelled && setState({ key, error: errorText(e) }),
    );
    return () => {
      cancelled = true;
    };
  }, [key]);

  const set = (patch: { held?: boolean; npage?: number }) => {
    const out = new URLSearchParams(params);
    const held = patch.held ?? stillHeld;
    if (held) out.delete("held");
    else out.set("held", "all");
    const npage = patch.npage ?? 0;
    if (npage === 0) out.delete("npage");
    else out.set("npage", String(npage));
    setParams(out);
  };

  const revoke = async (row: NotAppliedRow) => {
    if (!row.passId) return;
    setActionError(null);
    try {
      await adminApi.revoke(row.passId);
      setReload((n) => n + 1);
    } catch (e) {
      setActionError(errorText(e));
    }
  };

  const page = state?.key === key ? state.page : undefined;
  return (
    <section className={styles.card} aria-labelledby="not-applied-title">
      <h2 id="not-applied-title">Refunds and chargebacks not applied</h2>
      <p className={styles.muted}>
        Paddle reported these, but the server left the pass alone. Each row says why. Revoke the pass by hand when the customer
        shouldn&rsquo;t keep it.
      </p>
      <label className={styles.toggle}>
        <input type="checkbox" checked={stillHeld} onChange={(e) => set({ held: e.target.checked })} />
        Only where the customer still has the pass
      </label>
      {actionError && <Notice tone="error">{actionError}</Notice>}
      {state?.key === key && state.error && <Notice tone="error">{state.error}</Notice>}
      {state?.key !== key && <Spinner label="Loading" />}
      {page?.items.length === 0 && (
        <p>{stillHeld ? "Nothing waiting: no customer keeps a pass after an unapplied refund or chargeback." : "None yet."}</p>
      )}
      {page && page.items.length > 0 && (
        <>
          <div className={styles.tableWrap}>
            <table className={styles.table}>
              <caption className="visually-hidden">Refunds and chargebacks not applied</caption>
              <thead>
                <tr>
                  <th scope="col">Received</th>
                  <th scope="col">Why not applied</th>
                  <th scope="col">Customer</th>
                  <th scope="col">Purchase</th>
                  <th scope="col">Pass</th>
                  <th scope="col">
                    <span className="visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((row) => (
                  <tr key={row.eventId}>
                    <td>
                      {formatDateTime(row.receivedAt)}
                      <br />
                      <code className={styles.muted}>
                        {row.action} · {row.status}
                      </code>
                    </td>
                    <td>
                      <strong>{(row.reason && REASON_LABELS[row.reason]) ?? row.reason ?? "—"}</strong>
                      {row.detail && <span className={styles.statusDetail}>{row.detail}</span>}
                    </td>
                    <td className={styles.email}>{row.userEmail ?? <span className={styles.muted}>unknown</span>}</td>
                    <td>
                      {row.product ? PRODUCT_NAMES[row.product] : "—"}
                      {row.amountMinor !== null && row.currency && <> · {money(row.amountMinor, row.currency)}</>}
                      <br />
                      <code className={styles.muted}>{row.txnId}</code>
                    </td>
                    <td>
                      <span className={styles.status} data-status={row.passHeld ? "ACTIVE" : "REVOKED"}>
                        {row.passHeld ? "Still held" : row.passRevokedAt ? "Revoked" : "Ended"}
                      </span>
                    </td>
                    <td>
                      {row.passHeld && row.passId && (
                        <Button
                          variant="secondary"
                          onClick={() => void revoke(row)}
                          aria-label={`Revoke the pass of ${row.userEmail ?? "this customer"}`}
                        >
                          Revoke pass
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={page} onPage={(p) => set({ npage: p })} />
        </>
      )}
    </section>
  );
}
