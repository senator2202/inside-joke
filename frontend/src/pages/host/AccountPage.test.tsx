import { fireEvent, screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { AccessStatus } from "../../lib/billing";
import { apiError, mockFetch } from "../../test/fetchMock";
import { renderRoute } from "../../test/render";
import { AccountPage, freeGameLine } from "./AccountPage";

const me = { id: "u1", email: "ana@example.com", displayName: "Ana", role: "HOST", googleLinked: true };
const earlier = new Date(Date.now() - 165 * 24 * 3600_000).toISOString();
const later = new Date(Date.now() + 200 * 24 * 3600_000).toISOString();
const muchLater = new Date(Date.now() + 565 * 24 * 3600_000).toISOString();

function access(overrides: Partial<AccessStatus["access"]> = {}): AccessStatus {
  return {
    access: {
      freeGamesEnabled: true,
      freeGameAvailable: true,
      nextFreeGameAt: null,
      passes: [],
      upcomingPasses: [],
      nextGame: "FREE",
      paywallReason: null,
      ...overrides,
    },
    drainMode: false,
  };
}

describe("AccountPage", () => {
  it("sends signed-out visitors to sign in", async () => {
    mockFetch({ "GET /api/me": apiError(401, "UNAUTHORIZED") });
    renderRoute("/account", "/account", <AccountPage />);
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/login?returnTo=%2Faccount"));
  });

  it("shows the sign-in, passes and the free game status", async () => {
    mockFetch({
      "GET /api/me": { status: 200, body: me },
      "GET /api/billing/passes": {
        status: 200,
        body: access({
          freeGameAvailable: false,
          nextFreeGameAt: new Date(Date.now() + 2.5 * 86_400_000).toISOString(),
          nextGame: "HOST_PASS",
          passes: [
            {
              id: "e1",
              type: "HOST_PASS",
              startsAt: earlier,
              endsAt: later,
              monthlyGameLimit: 15,
              gamesLeftThisMonth: 12,
              grantedByAdmin: true,
            },
          ],
        }),
      },
    });
    renderRoute("/account", "/account", <AccountPage />);
    expect(await screen.findByText("ana@example.com")).toBeInTheDocument();
    expect(screen.getByText("Signed in with Google")).toBeInTheDocument();
    expect(await screen.findByText("Host Pass")).toBeInTheDocument();
    expect(screen.getByText(/12 of 15 games left this month/)).toBeInTheDocument();
    expect(screen.getByText("gift")).toBeInTheDocument();
    expect(screen.getByText("Next free game in 3 days.")).toBeInTheDocument();
    expect(screen.getByText("Receipts come by email from Paddle.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Create a party" })).toHaveAttribute("href", "/new");
  });

  it("lists a pass bought ahead after the running ones, with its start", async () => {
    mockFetch({
      "GET /api/me": { status: 200, body: me },
      "GET /api/billing/passes": {
        status: 200,
        body: access({
          nextGame: "HOST_PASS",
          passes: [{ id: "e1", type: "HOST_PASS", startsAt: earlier, endsAt: later, monthlyGameLimit: 15, gamesLeftThisMonth: 3 }],
          upcomingPasses: [
            { id: "e2", type: "HOST_PASS", startsAt: later, endsAt: muchLater, monthlyGameLimit: 15, gamesLeftThisMonth: 15 },
          ],
        }),
      },
    });
    renderRoute("/account", "/account", <AccountPage />);
    expect(await screen.findAllByText("Host Pass")).toHaveLength(2);
    const [running, ahead] = screen.getAllByRole("listitem");
    expect(running).toHaveTextContent(/Active until .* · 3 of 15 games left this month/);
    expect(ahead).toHaveTextContent("next");
    expect(ahead).toHaveTextContent(/Starts .*, until .* · 15 games a month/);
    expect(screen.queryByText(/No passes yet/)).not.toBeInTheDocument();
  });

  it("has an empty state and a retry when loading fails", async () => {
    mockFetch({
      "GET /api/me": { status: 200, body: { ...me, googleLinked: false } },
      "GET /api/billing/passes": [apiError(500, "INTERNAL"), { status: 200, body: access() }],
    });
    renderRoute("/account", "/account", <AccountPage />);
    expect(await screen.findByText("Couldn’t load.")).toBeInTheDocument();
    expect(screen.getByText("Signed in with a code sent by email")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    expect(await screen.findByText("No passes yet. The first game every week is free.")).toBeInTheDocument();
    expect(screen.getByText("Your free game this week is available.")).toBeInTheDocument();
  });

  it("deletes the account after confirmation and goes to the landing page", async () => {
    const mock = mockFetch({
      "GET /api/me": [{ status: 200, body: me }, apiError(401, "UNAUTHORIZED")],
      "GET /api/billing/passes": { status: 200, body: access() },
      "DELETE /api/me": { status: 204 },
    });
    renderRoute("/account", "/account", <AccountPage />);
    fireEvent.click(await screen.findByRole("button", { name: "Delete account" }));
    expect(screen.getByRole("alertdialog", { name: "Delete your account?" })).toHaveTextContent("deleted with no refund");
    fireEvent.click(screen.getByRole("button", { name: "Keep my account" }));
    expect(mock.callsTo("DELETE /api/me")).toHaveLength(0);
    fireEvent.click(screen.getByRole("button", { name: "Delete account" }));
    fireEvent.click(screen.getByRole("button", { name: "Delete for good" }));
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/?deleted=1"));
    expect(mock.callsTo("DELETE /api/me")).toHaveLength(1);
  });

  it("signs out", async () => {
    const mock = mockFetch({
      "GET /api/me": { status: 200, body: me },
      "GET /api/billing/passes": { status: 200, body: access() },
      "POST /api/auth/logout": { status: 204 },
    });
    renderRoute("/account", "/account", <AccountPage />);
    fireEvent.click(await screen.findByRole("button", { name: "Sign out" }));
    await waitFor(() => expect(mock.callsTo("POST /api/auth/logout")).toHaveLength(1));
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent(/^\/$/));
  });

  it("describes paused free games", () => {
    expect(freeGameLine(access({ freeGamesEnabled: false }).access)).toBe("Free games are paused right now.");
  });
});
