import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { apiError, mockFetch } from "../../test/fetchMock";
import { renderRoute } from "../../test/render";
import { LandingPage } from "./LandingPage";

describe("LandingPage", () => {
  it("pitches the game, splits hosts from guests and reports the visit with its ref", async () => {
    const mock = mockFetch({
      "GET /api/me": apiError(401, "UNAUTHORIZED"),
      "GET /api/status": { status: 200, body: { drainMode: false, freeGamesEnabled: true } },
      "POST /api/events": { status: 202 },
    });
    renderRoute("/?ref=S9<script>", "/", <LandingPage />);
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("The party game where the host knows too much about you");
    expect(screen.getByRole("link", { name: "Create a party" })).toHaveAttribute("href", "/new");
    expect(screen.getByRole("link", { name: "Join with a code" })).toHaveAttribute("href", "/join");
    expect(screen.getByRole("figure", { name: /Demo:/ })).toBeInTheDocument();
    expect(screen.getByText("$2.99")).toBeInTheDocument();
    await waitFor(() => expect(mock.callsTo("POST /api/events")).toHaveLength(1));
    expect(mock.callsTo("POST /api/events")[0]!.body).toMatchObject({ event: "landing_viewed", properties: { ref: "S9script" } });
  });

  it("pauses room creation during maintenance but keeps joining open", async () => {
    mockFetch({
      "GET /api/me": apiError(401, "UNAUTHORIZED"),
      "GET /api/status": { status: 200, body: { drainMode: true, freeGamesEnabled: true } },
      "POST /api/events": { status: 202 },
    });
    renderRoute("/", "/", <LandingPage />);
    expect(await screen.findByText("Room creation is paused")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Create a party" })).toBeDisabled();
    expect(screen.getByRole("link", { name: "Join with a code" })).toBeInTheDocument();
  });

  it("confirms a deleted account", () => {
    mockFetch({
      "GET /api/me": apiError(401, "UNAUTHORIZED"),
      "GET /api/status": { status: 200, body: { drainMode: false, freeGamesEnabled: true } },
      "POST /api/events": { status: 202 },
    });
    renderRoute("/?deleted=1", "/", <LandingPage />);
    expect(screen.getByText("Your account has been deleted")).toBeInTheDocument();
  });
});
