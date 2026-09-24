import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { apiError, mockFetch } from "../../test/fetchMock";
import { renderRoute } from "../../test/render";
import { AdminPage } from "./AdminPage";
import { money, usdFromMicros, type Metrics } from "./adminApi";

const admin = { id: "a1", email: "boss@example.com", displayName: null, role: "ADMIN", googleLinked: false };

const metrics: Metrics = {
  from: "2031-03-01",
  to: "2031-03-07",
  games: { total: 3, free: 2, paid: 1, completed: 1, avgPlayers: 5, endReasons: { COMPLETED: 1, IN_PROGRESS: 2 } },
  money: {
    revenue: [
      { currency: "USD", amountMinor: 1499 },
      { currency: "EUR", amountMinor: 279 },
    ],
    purchases: 3,
    byProduct: { HOST_PASS: 1, PARTY_PASS: 2 },
    refunds: 1,
  },
  ai: {
    costMicros: 3_500_000,
    freeGameCostMicros: 1_500_000,
    costPerGameMicros: 1_166_666,
    calls: 3,
    failures: 1,
    costByPurpose: { FINALE: 2_000_000 },
  },
  funnel: { newHosts: 2, activeHosts: 50, payingHosts: 1, conversion: 0.02 },
  live: { rooms: 4, inGame: 2, players: 13, viewers: 40 },
  days: Array.from({ length: 7 }, (_, i) => ({ date: `2031-03-0${i + 1}`, games: i % 3, paidGames: i === 4 ? 1 : 0, aiCostMicros: 1000 })),
};

const settings = {
  free_games_enabled: true,
  tts_enabled: true,
  daily_free_ai_budget_micros: 20_000_000,
  audience_cap: 2000,
  drain_mode: false,
};

describe("AdminPage", () => {
  it("is only for admins", async () => {
    mockFetch({ "GET /api/me": { status: 200, body: { ...admin, role: "HOST" } } });
    renderRoute("/admin", "/admin", <AdminPage />);
    expect(await screen.findByRole("heading", { name: "Admins only" })).toBeInTheDocument();
  });

  it("shows the key numbers and flags conversion under 3%", async () => {
    mockFetch({
      "GET /api/me": { status: 200, body: admin },
      "GET /api/admin/metrics": { status: 200, body: metrics },
      "GET /api/status": { status: 200, body: { drainMode: false, freeGamesEnabled: true, version: "0.5.0" } },
    });
    renderRoute("/admin", "/admin", <AdminPage />);
    expect(await screen.findByText("$14.99 + €2.79")).toBeInTheDocument();
    expect(await screen.findByText("v0.5.0")).toBeInTheDocument();
    expect(screen.getByText("2 free · 1 paid")).toBeInTheDocument();
    expect(screen.getByText("2.0%").closest("[data-warn]")).not.toBeNull();
    expect(screen.getByText("$3.50")).toBeInTheDocument();
    expect(screen.getByText("4 rooms")).toBeInTheDocument();
    expect(screen.getByRole("img", { name: "Games per day, 2031-03-01 to 2031-03-07" })).toBeInTheDocument();
  });

  it("finds a user, grants a pass and revokes it", async () => {
    const user = {
      id: "u9",
      email: "streamer@example.com",
      displayName: null,
      role: "HOST",
      googleLinked: true,
      createdAt: "2026-09-01T10:00:00Z",
      lastLoginAt: null,
      games: 7,
      passes: [],
    };
    const pass = {
      id: "e9",
      type: "HOST_PASS",
      endsAt: "2027-01-01T10:00:00Z",
      monthlyGameLimit: 15,
      gamesLeftThisMonth: 15,
      grantedByAdmin: true,
    };
    const mock = mockFetch({
      "GET /api/me": { status: 200, body: admin },
      "GET /api/admin/metrics": { status: 200, body: metrics },
      "GET /api/admin/users": { status: 200, body: [user] },
      "POST /api/admin/users/u9/passes": { status: 200, body: { ...user, passes: [pass] } },
      "POST /api/admin/passes/e9/revoke": { status: 200, body: { revoked: true } },
    });
    renderRoute("/admin", "/admin", <AdminPage />);
    fireEvent.click(await screen.findByRole("tab", { name: "Users & passes" }));
    fireEvent.change(screen.getByLabelText("Email contains"), { target: { value: "streamer" } });
    fireEvent.click(screen.getByRole("button", { name: "Search" }));
    const card = await screen.findByRole("article", { name: "streamer@example.com" });
    expect(within(card).getByText(/7 games/)).toBeInTheDocument();
    fireEvent.change(within(card).getByLabelText("Days"), { target: { value: "90" } });
    fireEvent.click(within(card).getByRole("button", { name: "Grant" }));
    expect(await within(card).findByText("Host Pass")).toBeInTheDocument();
    expect(mock.callsTo("POST /api/admin/users/u9/passes")[0]!.body).toEqual({ type: "HOST_PASS", days: 90 });
    expect(mock.callsTo("GET /api/admin/users")[0]!.path).toBe("/api/admin/users?email=streamer");
    fireEvent.click(within(card).getByRole("button", { name: "Revoke Host Pass of streamer@example.com" }));
    expect(await within(card).findByText("No active passes.")).toBeInTheDocument();
  });

  it("switches flags, saves limits in dollars and drains before a deploy", async () => {
    const mock = mockFetch({
      "GET /api/me": { status: 200, body: admin },
      "GET /api/admin/metrics": { status: 200, body: metrics },
      "GET /api/admin/settings": { status: 200, body: settings },
      "PUT /api/admin/settings/free_games_enabled": { status: 200, body: { ...settings, free_games_enabled: false } },
      "PUT /api/admin/settings/daily_free_ai_budget_micros": {
        status: 200,
        body: { ...settings, daily_free_ai_budget_micros: 12_500_000 },
      },
      "POST /api/admin/drain": { status: 200, body: { drainMode: true, live: { rooms: 3, inGame: 1, players: 9, viewers: 0 } } },
    });
    renderRoute("/admin", "/admin", <AdminPage />);
    fireEvent.click(await screen.findByRole("tab", { name: "Flags & drain" }));
    fireEvent.click(await screen.findByLabelText("Free games (1 per host per week)"));
    expect(await screen.findByText("Free games (1 per host per week) saved.")).toBeInTheDocument();
    expect(mock.callsTo("PUT /api/admin/settings/free_games_enabled")[0]!.body).toEqual({ value: false });

    fireEvent.change(screen.getByLabelText("Daily AI budget for free games, USD"), { target: { value: "12.50" } });
    fireEvent.click(screen.getByRole("button", { name: "Save budget" }));
    await waitFor(() =>
      expect(mock.callsTo("PUT /api/admin/settings/daily_free_ai_budget_micros")[0]?.body).toEqual({ value: 12_500_000 }),
    );

    fireEvent.click(screen.getByRole("button", { name: "Turn drain mode on" }));
    expect(await screen.findByText("Live now: 3 rooms, 1 in a game, 9 players.")).toBeInTheDocument();
    expect(screen.getByText(/On: no new rooms can be created/)).toBeInTheDocument();
    expect(mock.callsTo("POST /api/admin/drain")[0]!.body).toEqual({ enabled: true });
  });

  it("replays a stuck payment event", async () => {
    const stuck = {
      provider: "PADDLE",
      eventId: "evt_1",
      eventType: "transaction.completed",
      receivedAt: "2026-09-21T10:00:00Z",
      lastError: "unknown price pri_x",
    };
    mockFetch({
      "GET /api/me": { status: 200, body: admin },
      "GET /api/admin/metrics": { status: 200, body: metrics },
      "GET /api/admin/webhooks/failed": [
        { status: 200, body: [stuck] },
        { status: 200, body: [] },
      ],
      "POST /api/admin/webhooks/evt_1/replay": { status: 200, body: { processed: true, result: "GRANTED" } },
    });
    renderRoute("/admin", "/admin", <AdminPage />);
    fireEvent.click(await screen.findByRole("tab", { name: "Payment issues" }));
    expect(await screen.findByText("unknown price pri_x")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Replay" }));
    expect(await screen.findByText("Nothing stuck. 🎉")).toBeInTheDocument();
  });

  it("formats money in each currency's own minor units", () => {
    expect(money(1499, "USD")).toBe("$14.99");
    expect(money(500, "JPY")).toBe("¥500");
    expect(usdFromMicros(1_166_666)).toBe("$1.17");
    expect(usdFromMicros(0)).toBe("$0.00");
  });

  it("reports a failed metrics load", async () => {
    mockFetch({ "GET /api/me": { status: 200, body: admin }, "GET /api/admin/metrics": apiError(400, "VALIDATION_FAILED") });
    renderRoute("/admin", "/admin", <AdminPage />);
    expect(await screen.findByRole("alert")).toHaveTextContent("VALIDATION_FAILED");
  });
});
