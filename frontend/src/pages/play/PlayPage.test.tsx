import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { loadSeat, saveSeat } from "../../lib/game/storage";
import { setSocketFactoryForTests } from "../../lib/game/useGame";
import { apiError, mockFetch } from "../../test/fetchMock";
import { fakeSockets, roomState } from "../../test/fakeSocket";
import { renderRoute } from "../../test/render";
import { PlayPage } from "./PlayPage";

describe("PlayPage", () => {
  let net: ReturnType<typeof fakeSockets>;
  beforeEach(() => {
    net = fakeSockets();
    setSocketFactoryForTests(net.factory);
    mockFetch({ "GET /api/me": apiError(401, "UNAUTHORIZED") });
  });
  afterEach(() => setSocketFactoryForTests(undefined));

  it("sends phones without a seat to the join page", async () => {
    renderRoute("/play/KWMP", "/play/:code", <PlayPage />);
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/j/KWMP"));
  });

  it("follows the server's snapshots and lets the captain start", async () => {
    saveSeat("KWMP", { token: "tok-p1", kind: "player", playerId: "p1" });
    renderRoute("/play/KWMP", "/play/:code", <PlayPage />);
    const socket = net.last();
    act(() => socket.open());
    expect(socket.sent[0]?.data).toEqual({ token: "tok-p1" });
    act(() => socket.accept(roomState()));
    expect(await screen.findByText("You\u2019re in, Masha! Look at the screen")).toBeInTheDocument();
    const start = screen.getByRole("button", { name: "Everyone\u2019s here: start" });
    expect(start).toBeDisabled();

    const three = roomState({
      version: 2,
      players: [...roomState().players!, { id: "p3", name: "Olya", emoji: "🐸", connected: true, score: 0, captain: false, status: "" }],
    });
    act(() => socket.receive("state", null, three));
    await waitFor(() => expect(start).toBeEnabled());
    fireEvent.click(start);
    expect(socket.requests("game.start")).toHaveLength(1);

    act(() => socket.receive("state", null, roomState({ version: 1, phase: "INTAKE" })));
    expect(screen.getByText("You\u2019re in, Masha! Look at the screen")).toBeInTheDocument();
  });

  it("shows the removal screen and forgets the seat when kicked", async () => {
    saveSeat("KWMP", { token: "tok-p1", kind: "player", playerId: "p1" });
    renderRoute("/play/KWMP", "/play/:code", <PlayPage />);
    const socket = net.last();
    act(() => {
      socket.open();
      socket.accept(roomState({ you: { role: "PLAYER", playerId: "p1", name: "Masha", emoji: "🦊" } }));
    });
    act(() => {
      socket.receive("kicked", null);
      socket.drop(4403);
    });
    expect(await screen.findByText("The host removed you from the room")).toBeInTheDocument();
    expect(loadSeat("KWMP", "player")).toBeNull();
  });

  it("hides the secret button while the player owes an answer", async () => {
    saveSeat("KWMP", { token: "tok-p1", kind: "player", playerId: "p1" });
    renderRoute("/play/KWMP", "/play/:code", <PlayPage />);
    const socket = net.last();
    act(() => {
      socket.open();
      socket.accept(
        roomState({
          phase: "ANSWERING",
          deadline: Date.now() + 60_000,
          you: { role: "PLAYER", playerId: "p1", name: "Masha", emoji: "🦊", assignments: [{ duelId: "d0", prompt: "Worst superpower?" }] },
        }),
      );
    });
    expect(await screen.findByLabelText("Worst superpower?")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Give the host a secret" })).not.toBeInTheDocument();
    act(() =>
      socket.receive(
        "state",
        null,
        roomState({
          version: 2,
          phase: "REVEAL",
          you: { role: "PLAYER", playerId: "p1", name: "Masha", emoji: "🦊", roundPoints: 400, rank: 1, score: 400 },
        }),
      ),
    );
    expect(await screen.findByText("+400")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Give the host a secret" })).toBeInTheDocument();
  });

  it("does not offer secrets when the host has no way to check them", async () => {
    saveSeat("KWMP", { token: "tok-p1", kind: "player", playerId: "p1" });
    renderRoute("/play/KWMP", "/play/:code", <PlayPage />);
    const socket = net.last();
    act(() => {
      socket.open();
      socket.accept(roomState({ secretsOpen: false }));
    });
    expect(await screen.findByText("Secrets are off tonight: the host has no way to check them.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "🤫 Spill a secret" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Give the host a secret" })).not.toBeInTheDocument();

    act(() => socket.receive("state", null, roomState({ version: 2, secretsOpen: true })));
    expect(await screen.findByRole("button", { name: "🤫 Spill a secret" })).toBeInTheDocument();
  });
});
