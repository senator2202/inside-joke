import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { loadSeat, savePref } from "../../lib/game/storage";
import { apiError, mockFetch } from "../../test/fetchMock";
import { renderRoute } from "../../test/render";
import { NewPartyPage, accessLine, type AccessStatus } from "./NewPartyPage";

const me = { id: "u1", email: "ana@example.com", displayName: "Ana", role: "HOST", googleLinked: true };
const free: AccessStatus = {
  access: {
    freeGamesEnabled: true,
    freeGameAvailable: true,
    nextFreeGameAt: null,
    passes: [],
    upcomingPasses: [],
    nextGame: "FREE",
    paywallReason: null,
  },
  drainMode: false,
};

describe("NewPartyPage", () => {
  it("sends signed-out visitors to sign in first", async () => {
    mockFetch({ "GET /api/me": apiError(401, "UNAUTHORIZED") });
    renderRoute("/new", "/new", <NewPartyPage />);
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/login?returnTo=%2Fnew"));
  });

  it("creates a room with the chosen settings and opens the shared screen", async () => {
    const mock = mockFetch({
      "GET /api/me": { status: 200, body: me },
      "GET /api/billing/passes": { status: 200, body: free },
      "POST /api/rooms": { status: 201, body: { code: "KWMP", screenToken: "owner-tok", joinUrl: "http://localhost/j/KWMP" } },
    });
    renderRoute("/new", "/new", <NewPartyPage />);
    expect(await screen.findByText("Your free game this week is available")).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByLabelText(/Long/));
    await user.click(screen.getByLabelText(/Streamer/));
    await user.click(screen.getByLabelText(/Hide the room code/));
    await user.click(screen.getByRole("button", { name: "Create room" }));
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/screen/KWMP"));
    expect(mock.callsTo("POST /api/rooms")[0]!.body).toEqual({
      company: "FRIENDS",
      tone: "CHEEKY",
      length: "LONG",
      mode: "STREAMER",
      hideCode: true,
      language: "en",
    });
    expect(loadSeat("KWMP", "owner")?.token).toBe("owner-tok");
  });

  it("tells the host who came, and keeps coworkers away from Spicy", async () => {
    const mock = mockFetch({
      "GET /api/me": { status: 200, body: me },
      "GET /api/billing/passes": { status: 200, body: free },
      "POST /api/rooms": { status: 201, body: { code: "BCDF", screenToken: "t", joinUrl: "x" } },
    });
    renderRoute("/new", "/new", <NewPartyPage />);
    const user = userEvent.setup();
    await user.click(await screen.findByLabelText(/Spicy 18\+/));
    await user.click(screen.getByRole("button", { name: "Yes" }));
    expect(screen.getByLabelText(/Spicy 18\+/)).toBeChecked();

    await user.click(screen.getByLabelText(/Colleagues/));
    expect(screen.getByLabelText(/Spicy 18\+/)).toBeDisabled();
    expect(screen.getByLabelText(/Cheeky/)).toBeChecked();
    await user.type(screen.getByLabelText("A few words about the group (optional)"), "  sales team  ");
    await user.click(screen.getByRole("button", { name: "Create room" }));
    await waitFor(() => expect(mock.callsTo("POST /api/rooms")).toHaveLength(1));
    expect(mock.callsTo("POST /api/rooms")[0]!.body).toMatchObject({ company: "COLLEAGUES", context: "sales team", tone: "CHEEKY" });
  });

  it("asks about 18+ again when Spicy comes back from last time, then creates the room", async () => {
    savePref("newParty", JSON.stringify({ tone: "SPICY", length: "SHORT", mode: "STANDARD", language: "en" }));
    const mock = mockFetch({
      "GET /api/me": { status: 200, body: me },
      "GET /api/billing/passes": { status: 200, body: free },
      "POST /api/rooms": { status: 201, body: { code: "BCDF", screenToken: "t", joinUrl: "x" } },
    });
    renderRoute("/new", "/new", <NewPartyPage />);
    const user = userEvent.setup();
    expect(await screen.findByLabelText(/Spicy 18\+/)).toBeChecked();
    await user.click(screen.getByRole("button", { name: "Create room" }));
    expect(screen.getByRole("dialog", { name: "Is everyone over 18?" })).toBeInTheDocument();
    expect(mock.callsTo("POST /api/rooms")).toHaveLength(0);

    await user.click(screen.getByRole("button", { name: "Yes" }));
    await waitFor(() => expect(mock.callsTo("POST /api/rooms")).toHaveLength(1));
    expect(mock.callsTo("POST /api/rooms")[0]!.body).toMatchObject({ tone: "SPICY", adultsConfirmed: true });
  });

  it("says why a room couldn't be created when it knows", async () => {
    mockFetch({
      "GET /api/me": { status: 200, body: me },
      "GET /api/billing/passes": { status: 200, body: free },
      "POST /api/rooms": [apiError(429, "RATE_LIMITED"), apiError(400, "VALIDATION_FAILED", { fields: { context: "too long" } })],
    });
    renderRoute("/new", "/new", <NewPartyPage />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Create room" }));
    expect(await screen.findByText("Too many attempts. Wait a minute.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Create room" }));
    expect(await screen.findByText(/Shorten the description of the group/)).toBeInTheDocument();
  });

  it("asks whether everyone is over 18 before choosing Spicy", async () => {
    const mock = mockFetch({
      "GET /api/me": { status: 200, body: me },
      "GET /api/billing/passes": { status: 200, body: free },
      "POST /api/rooms": { status: 201, body: { code: "BCDF", screenToken: "t", joinUrl: "x" } },
    });
    renderRoute("/new", "/new", <NewPartyPage />);
    const user = userEvent.setup();
    await user.click(await screen.findByLabelText(/Spicy 18\+/));
    expect(screen.getByRole("dialog", { name: "Is everyone over 18?" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Choose Cheeky" }));
    expect(screen.getByLabelText(/Cheeky/)).toBeChecked();

    await user.click(screen.getByLabelText(/Spicy 18\+/));
    await user.click(screen.getByRole("button", { name: "Yes" }));
    expect(screen.getByLabelText(/Spicy 18\+/)).toBeChecked();
    await user.click(screen.getByRole("button", { name: "Create room" }));
    await waitFor(() => expect(mock.callsTo("POST /api/rooms")).toHaveLength(1));
    expect(mock.callsTo("POST /api/rooms")[0]!.body).toMatchObject({ tone: "SPICY", adultsConfirmed: true });
  });

  it("disables creation during maintenance and reports failures", async () => {
    mockFetch({
      "GET /api/me": { status: 200, body: me },
      "GET /api/billing/passes": { status: 200, body: { ...free, drainMode: true } },
    });
    renderRoute("/new", "/new", <NewPartyPage />);
    expect(await screen.findByText("Updating the service")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Create room" })).toBeDisabled();
  });

  it("describes every kind of access", () => {
    const pass = (type: "PARTY_PASS" | "HOST_PASS", left: number | null) => ({
      id: "e",
      type,
      startsAt: "2026-09-22T20:00:00Z",
      endsAt: "2026-09-23T20:00:00Z",
      monthlyGameLimit: left === null ? null : 15,
      gamesLeftThisMonth: left,
    });
    expect(accessLine({ ...free.access, nextGame: "HOST_PASS", passes: [pass("HOST_PASS", 12)] })).toBe(
      "Host Pass: 12 games left this month",
    );
    expect(accessLine({ ...free.access, nextGame: "PARTY_PASS", passes: [pass("PARTY_PASS", null)] })).toMatch(/^Party Pass active until/);
    expect(accessLine({ ...free.access, nextGame: "PAYWALL", paywallReason: "PAYWALL_FREE_LIMIT" })).toBe(
      "Free game already played. You can buy a pass once your friends are here.",
    );
  });
});
