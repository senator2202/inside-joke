import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { loadSeat } from "../../lib/game/storage";
import { setSocketFactoryForTests } from "../../lib/game/useGame";
import { apiError, mockFetch } from "../../test/fetchMock";
import { fakeSockets, roomState } from "../../test/fakeSocket";
import { renderRoute } from "../../test/render";
import { AudiencePage } from "./AudiencePage";

describe("AudiencePage", () => {
  let net: ReturnType<typeof fakeSockets>;
  beforeEach(() => {
    net = fakeSockets();
    setSocketFactoryForTests(net.factory);
  });
  afterEach(() => setSocketFactoryForTests(undefined));

  it("joins with the viewers' key from the link, not a room code", async () => {
    const mock = mockFetch({
      "GET /api/me": apiError(401, "UNAUTHORIZED"),
      "POST /api/audiences/QXZTRPLMNB/viewers": { status: 201, body: { audienceToken: "aud-tok" } },
    });
    renderRoute("/w/qxztrplmnb", "/w/:key", <AudiencePage />);
    fireEvent.click(screen.getByRole("button", { name: "Watch and vote" }));
    await waitFor(() => expect(net.sockets).toHaveLength(1));
    expect(mock.callsTo("POST /api/audiences/QXZTRPLMNB/viewers")).toHaveLength(1);
    expect(loadSeat("QXZTRPLMNB", "audience")?.token).toBe("aud-tok");
    act(() => {
      net.last().open();
      net.last().accept(roomState({ code: undefined, players: undefined, lobby: undefined, you: { role: "AUDIENCE" } }));
    });
    expect(await screen.findByText("Voting opens in the next round")).toBeInTheDocument();
  });

  it("explains rooms that aren't open to viewers", async () => {
    mockFetch({ "GET /api/me": apiError(401, "UNAUTHORIZED"), "POST /api/audiences/ZZZZZZZZZZ/viewers": apiError(404, "ROOM_NOT_FOUND") });
    renderRoute("/w/ZZZZZZZZZZ", "/w/:key", <AudiencePage />);
    fireEvent.click(screen.getByRole("button", { name: "Watch and vote" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("This room doesn't exist.");
  });
});
