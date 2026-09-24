import { useEffect, useState, type FormEvent } from "react";
import { Link, Navigate, useSearchParams } from "react-router";
import { Button } from "../../components/Button";
import { HostLayout } from "../../components/HostLayout";
import { Notice } from "../../components/Notice";
import { Spinner } from "../../components/Spinner";
import { api, isApiError } from "../../lib/api";
import { useAuth } from "../../lib/auth";
import { PRODUCT_NAMES, formatPassEnd, type ProductId } from "../../lib/billing";
import { adminApi, isoDay, money, usdFromMicros, type AdminUser, type Live, type Metrics, type Settings } from "./adminApi";
import { AiTab } from "./AiTab";
import { PassesTab } from "./PassesTab";
import { PaymentIssues } from "./PaymentIssues";
import styles from "./Admin.module.css";

type Tab = "overview" | "passes" | "ai" | "users" | "settings" | "payments";
const TABS: { id: Tab; label: string }[] = [
  { id: "overview", label: "Overview" },
  { id: "passes", label: "Passes" },
  { id: "ai", label: "AI" },
  { id: "users", label: "Users & passes" },
  { id: "settings", label: "Flags & drain" },
  { id: "payments", label: "Payment issues" },
];

function errorMessage(e: unknown): string {
  return isApiError(e) ? e.message : "Something went wrong.";
}

/** Admin panel: metrics, passes for streamers and testers, flags and limits, drain mode, stuck payment events. */
export function AdminPage() {
  const { me, loading } = useAuth();
  const [params, setParams] = useSearchParams();
  const tab: Tab = TABS.find((t) => t.id === params.get("tab"))?.id ?? "overview";
  // Each tab keeps its own URL state; switching tabs starts clean.
  const setTab = (next: Tab) => setParams(next === "overview" ? {} : { tab: next });
  const version = useServiceVersion();
  if (loading)
    return (
      <HostLayout>
        <Spinner label="Loading" />
      </HostLayout>
    );
  if (!me) return <Navigate to={`/login?returnTo=${encodeURIComponent("/admin")}`} replace />;
  if (me.role !== "ADMIN") {
    return (
      <HostLayout narrow>
        <h1 className={styles.title}>Admins only</h1>
        <p>
          This page is for the Inside Joke team. <Link to="/">Back to the start page</Link>
        </p>
      </HostLayout>
    );
  }
  return (
    <HostLayout>
      <div className={styles.page}>
        <div className={styles.titleRow}>
          <h1 className={styles.title}>Admin</h1>
          {version && (
            <span className={styles.version} title="Service version (GET /api/status)">
              v{version}
            </span>
          )}
        </div>
        <nav className={styles.tabs} role="tablist" aria-label="Admin sections">
          {TABS.map((t) => (
            <button key={t.id} type="button" role="tab" aria-selected={tab === t.id} onClick={() => setTab(t.id)}>
              {t.label}
            </button>
          ))}
        </nav>
        <section role="tabpanel" aria-label={TABS.find((t) => t.id === tab)!.label}>
          {tab === "overview" && <Overview />}
          {tab === "users" && <Users />}
          {tab === "settings" && <SettingsPanel />}
          {tab === "passes" && <PassesTab />}
          {tab === "ai" && <AiTab />}
          {tab === "payments" && <PaymentIssues />}
        </section>
      </div>
    </HostLayout>
  );
}

/** The running service's version, so admins can tell which release they're looking at. */
function useServiceVersion(): string | null {
  const [version, setVersion] = useState<string | null>(null);
  useEffect(() => {
    let cancelled = false;
    api<{ version?: string }>("/api/status").then(
      (s) => !cancelled && setVersion(s.version ?? null),
      () => undefined,
    );
    return () => {
      cancelled = true;
    };
  }, []);
  return version;
}

// ------------------------------------------------------------------ overview

function Overview() {
  const today = new Date();
  const [from, setFrom] = useState(isoDay(new Date(today.getTime() - 29 * 86_400_000)));
  const [to, setTo] = useState(isoDay(today));
  const [range, setRange] = useState({ from, to });
  const [data, setData] = useState<Metrics | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    adminApi.metrics(range.from, range.to).then(
      (m) => !cancelled && setData(m),
      (e: unknown) => !cancelled && setError(errorMessage(e)),
    );
    return () => {
      cancelled = true;
    };
  }, [range]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setRange({ from, to });
  };

  return (
    <div className={styles.stack}>
      <form className={styles.range} onSubmit={submit}>
        <label>
          From <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} required />
        </label>
        <label>
          To <input type="date" value={to} onChange={(e) => setTo(e.target.value)} required />
        </label>
        <Button variant="secondary" type="submit">
          Show
        </Button>
      </form>
      {error && <Notice tone="error">{error}</Notice>}
      {!data && !error && <Spinner label="Loading metrics" />}
      {data && (
        <>
          <div className={styles.kpis}>
            <Kpi label="Games" value={String(data.games.total)} detail={`${data.games.free} free · ${data.games.paid} paid`} />
            <Kpi
              label="Finished"
              value={data.games.total ? `${Math.round((data.games.completed * 100) / data.games.total)}%` : "—"}
              detail={`${data.games.avgPlayers} players on average`}
            />
            <Kpi
              label="Revenue"
              value={data.money.revenue.length ? data.money.revenue.map((r) => money(r.amountMinor, r.currency)).join(" + ") : "—"}
              detail={`${data.money.purchases} purchases · ${data.money.refunds} refunds`}
            />
            <Kpi
              label="Conversion"
              value={`${(data.funnel.conversion * 100).toFixed(1)}%`}
              warn={data.funnel.activeHosts > 0 && data.funnel.conversion < 0.03}
              detail={`${data.funnel.payingHosts} paying of ${data.funnel.activeHosts} active hosts · target 3%`}
            />
            <Kpi
              label="AI cost"
              value={usdFromMicros(data.ai.costMicros)}
              detail={`${usdFromMicros(data.ai.costPerGameMicros)} per game · ${usdFromMicros(data.ai.freeGameCostMicros)} on free games`}
            />
            <Kpi
              label="AI failures"
              value={String(data.ai.failures)}
              detail={`of ${data.ai.calls} calls`}
              warn={data.ai.calls > 0 && data.ai.failures / data.ai.calls > 0.05}
            />
            <Kpi label="New hosts" value={String(data.funnel.newHosts)} detail="signed up in the range" />
            <LiveKpi live={data.live} />
          </div>
          <DayChart days={data.days} />
          <div className={styles.split}>
            <Breakdown
              title="Passes sold"
              rows={Object.entries(data.money.byProduct).map(([k, v]) => [PRODUCT_NAMES[k as ProductId] ?? k, String(v)])}
            />
            <Breakdown
              title="How games ended"
              rows={Object.entries(data.games.endReasons).map(([k, v]) => [k.replaceAll("_", " ").toLowerCase(), String(v)])}
            />
            <Breakdown
              title="AI cost by purpose"
              rows={Object.entries(data.ai.costByPurpose).map(([k, v]) => [k.replaceAll("_", " ").toLowerCase(), usdFromMicros(v)])}
            />
          </div>
        </>
      )}
    </div>
  );
}

function Kpi({ label, value, detail, warn }: { label: string; value: string; detail?: string; warn?: boolean }) {
  return (
    <div className={styles.kpi} data-warn={warn || undefined}>
      <p className={styles.kpiLabel}>{label}</p>
      <p className={styles.kpiValue}>{value}</p>
      {detail && <p className={styles.kpiDetail}>{detail}</p>}
    </div>
  );
}

function LiveKpi({ live }: { live: Live }) {
  return (
    <Kpi
      label="Live now"
      value={`${live.rooms} rooms`}
      detail={`${live.inGame} in a game · ${live.players} players · ${live.viewers} viewers`}
    />
  );
}

function Breakdown({ title, rows }: { title: string; rows: [string, string][] }) {
  return (
    <div className={styles.card}>
      <h2>{title}</h2>
      {rows.length === 0 ? (
        <p className={styles.muted}>Nothing yet.</p>
      ) : (
        <table className={styles.table}>
          <tbody>
            {rows.map(([k, v]) => (
              <tr key={k}>
                <td>{k}</td>
                <td className={styles.num}>{v}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

function DayChart({ days }: { days: Metrics["days"] }) {
  const maxGames = Math.max(1, ...days.map((d) => d.games));
  const w = 720;
  const h = 180;
  const bar = w / days.length;
  return (
    <figure className={styles.card}>
      <h2>Games per day</h2>
      <div className={styles.chartWrap}>
        <svg viewBox={`0 0 ${w} ${h + 22}`} role="img" aria-label={`Games per day, ${days[0]?.date} to ${days.at(-1)?.date}`}>
          <line x1="0" y1={h} x2={w} y2={h} className={styles.axis} />
          {days.map((d, i) => {
            const total = (d.games / maxGames) * (h - 10);
            const paid = (d.paidGames / maxGames) * (h - 10);
            return (
              <g key={d.date}>
                <title>{`${d.date}: ${d.games} games (${d.paidGames} paid), AI ${usdFromMicros(d.aiCostMicros)}`}</title>
                <rect x={i * bar + 2} y={h - total} width={Math.max(1, bar - 4)} height={total} className={styles.barFree} />
                <rect x={i * bar + 2} y={h - paid} width={Math.max(1, bar - 4)} height={paid} className={styles.barPaid} />
                {(i === 0 || i === days.length - 1 || i % 7 === 0) && (
                  <text x={i * bar + bar / 2} y={h + 16} textAnchor="middle" className={styles.tick}>
                    {d.date.slice(5)}
                  </text>
                )}
              </g>
            );
          })}
        </svg>
      </div>
      <p className={styles.legend}>
        <span className={styles.swatchPaid} /> paid <span className={styles.swatchFree} /> free · peak {maxGames}
      </p>
    </figure>
  );
}

// ------------------------------------------------------------------ users

function Users() {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<AdminUser[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const search = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    adminApi.users(query.trim()).then(setResults, (err: unknown) => setError(errorMessage(err)));
  };
  const replace = (u: AdminUser) => setResults((list) => list?.map((x) => (x.id === u.id ? u : x)) ?? null);
  return (
    <div className={styles.stack}>
      <form className={styles.range} onSubmit={search} role="search">
        <label>
          Email contains <input value={query} onChange={(e) => setQuery(e.target.value)} minLength={2} required />
        </label>
        <Button variant="secondary" type="submit">
          Search
        </Button>
      </form>
      {error && <Notice tone="error">{error}</Notice>}
      {results?.length === 0 && <p className={styles.muted}>No users found.</p>}
      {results?.map((u) => (
        <UserCard key={u.id} user={u} onChange={replace} />
      ))}
    </div>
  );
}

function UserCard({ user, onChange }: { user: AdminUser; onChange: (u: AdminUser) => void }) {
  const [type, setType] = useState<ProductId>("HOST_PASS");
  const [days, setDays] = useState(30);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const grant = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      onChange(await adminApi.grant(user.id, type, days));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  };
  const revoke = async (passId: string) => {
    setError(null);
    try {
      await adminApi.revoke(passId);
      onChange({ ...user, passes: user.passes.filter((p) => p.id !== passId) });
    } catch (err) {
      setError(errorMessage(err));
    }
  };
  return (
    <article className={styles.card} aria-label={user.email}>
      <h2 className={styles.email}>{user.email}</h2>
      <p className={styles.muted}>
        {user.role === "ADMIN" ? "Admin · " : ""}
        {user.googleLinked ? "Google" : "Email code"} · joined {new Date(user.createdAt).toLocaleDateString("en-GB")}
        {user.lastLoginAt ? ` · last seen ${new Date(user.lastLoginAt).toLocaleDateString("en-GB")}` : ""} · {user.games} games
      </p>
      {user.passes.length === 0 ? (
        <p>No active passes.</p>
      ) : (
        <ul className={styles.passes}>
          {user.passes.map((p) => (
            <li key={p.id}>
              <strong>{PRODUCT_NAMES[p.type]}</strong> until {formatPassEnd(p.endsAt)}
              {p.gamesLeftThisMonth !== null && ` · ${p.gamesLeftThisMonth} games left this month`}
              {p.grantedByAdmin && " · granted"}
              <Button variant="quiet" onClick={() => void revoke(p.id)} aria-label={`Revoke ${PRODUCT_NAMES[p.type]} of ${user.email}`}>
                Revoke
              </Button>
            </li>
          ))}
        </ul>
      )}
      <form className={styles.grant} onSubmit={(e) => void grant(e)}>
        <label>
          Pass
          <select value={type} onChange={(e) => setType(e.target.value as ProductId)}>
            <option value="HOST_PASS">Host Pass</option>
            <option value="PARTY_PASS">Party Pass</option>
          </select>
        </label>
        <label>
          Days <input type="number" min={1} max={366} value={days} onChange={(e) => setDays(Number(e.target.value))} />
        </label>
        <Button type="submit" loading={busy}>
          Grant
        </Button>
      </form>
      {error && <Notice tone="error">{error}</Notice>}
    </article>
  );
}

// ------------------------------------------------------------------ settings & drain

const FLAG_LABELS: Record<"free_games_enabled" | "tts_enabled", string> = {
  free_games_enabled: "Free games (1 per host per week)",
  tts_enabled: "Host voice (text-to-speech)",
};

function SettingsPanel() {
  const [settings, setSettings] = useState<Settings | null>(null);
  const [live, setLive] = useState<Live | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const [budget, setBudget] = useState("");
  const [cap, setCap] = useState("");

  useEffect(() => {
    adminApi.settings().then(
      (s) => {
        setSettings(s);
        setBudget((s.daily_free_ai_budget_micros / 1_000_000).toFixed(2));
        setCap(String(s.audience_cap));
      },
      (e: unknown) => setError(errorMessage(e)),
    );
  }, []);

  const save = async (key: keyof Settings, value: boolean | number, label: string) => {
    setError(null);
    setSaved(null);
    try {
      setSettings(await adminApi.updateSetting(key, value));
      setSaved(`${label} saved.`);
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const drain = async (enabled: boolean) => {
    setError(null);
    try {
      const r = await adminApi.drain(enabled);
      setSettings((s) => (s ? { ...s, drain_mode: r.drainMode } : s));
      setLive(r.live);
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  if (!settings) return error ? <Notice tone="error">{error}</Notice> : <Spinner label="Loading settings" />;
  return (
    <div className={styles.stack}>
      {error && <Notice tone="error">{error}</Notice>}
      {saved && <Notice tone="success">{saved}</Notice>}
      <div className={styles.card} data-alarm={settings.drain_mode || undefined}>
        <h2>Drain mode</h2>
        <p>
          {settings.drain_mode
            ? "On: no new rooms can be created. Running rooms play to the end."
            : "Off. Turn it on before a deploy so no new rooms start; deploy once live rooms reach zero."}
        </p>
        {live && (
          <p className={styles.muted}>
            Live now: {live.rooms} rooms, {live.inGame} in a game, {live.players} players.
          </p>
        )}
        <div className={styles.row}>
          <Button variant={settings.drain_mode ? "secondary" : "danger"} onClick={() => void drain(!settings.drain_mode)}>
            {settings.drain_mode ? "Turn drain mode off" : "Turn drain mode on"}
          </Button>
          {settings.drain_mode && (
            <Button variant="quiet" onClick={() => void drain(true)}>
              Refresh live rooms
            </Button>
          )}
        </div>
      </div>
      <div className={styles.card}>
        <h2>Flags</h2>
        {(Object.keys(FLAG_LABELS) as (keyof typeof FLAG_LABELS)[]).map((key) => (
          <label key={key} className={styles.toggle}>
            <input type="checkbox" checked={settings[key]} onChange={(e) => void save(key, e.target.checked, FLAG_LABELS[key])} />
            {FLAG_LABELS[key]}
          </label>
        ))}
      </div>
      <form
        className={styles.card}
        onSubmit={(e) => {
          e.preventDefault();
          void save("daily_free_ai_budget_micros", Math.round(Number(budget) * 1_000_000), "Daily free-game AI budget");
        }}
      >
        <h2>Limits</h2>
        <label className={styles.field}>
          Daily AI budget for free games, USD
          <input type="number" min={0} step="0.01" value={budget} onChange={(e) => setBudget(e.target.value)} />
        </label>
        <Button variant="secondary" type="submit">
          Save budget
        </Button>
      </form>
      <form
        className={styles.card}
        onSubmit={(e) => {
          e.preventDefault();
          void save("audience_cap", Number(cap), "Viewer limit");
        }}
      >
        <label className={styles.field}>
          Viewers per stream room
          <input type="number" min={0} max={20000} value={cap} onChange={(e) => setCap(e.target.value)} />
        </label>
        <Button variant="secondary" type="submit">
          Save viewer limit
        </Button>
      </form>
    </div>
  );
}
