import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { loadSeat, saveSeat } from "../../lib/game/storage";
import { apiError, mockFetch } from "../../test/fetchMock";
import { renderRoute } from "../../test/render";
import { JoinPage } from "./JoinPage";

const room = {
  code: "KWMP",
  phase: "LOBBY",
  mode: "STANDARD",
  players: 2,
  maxPlayers: 8,
  locked: false,
  full: false,
  joinable: true,
  audienceOpen: false,
  hideCode: false,
};

describe("JoinPage", () => {
  it("checks the code from the QR link and joins with a name", async () => {
    const mock = mockFetch({
      "GET /api/me": apiError(401, "UNAUTHORIZED"),
      "GET /api/rooms/KWMP": { status: 200, body: room },
      "POST /api/rooms/KWMP/players": { status: 201, body: { playerId: "p3", playerToken: "tok-p3" } },
    });
    renderRoute("/j/kwmp", "/j/:code", <JoinPage />);
    expect(await screen.findByText("Room found ✓")).toBeInTheDocument();
    const button = screen.getByRole("button", { name: "Join the game" });
    expect(button).toBeDisabled();
    await userEvent.setup().type(screen.getByLabelText("Your name"), " Olya ");
    await userEvent.setup().click(button);
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/play/KWMP"));
    const body = mock.callsTo("POST /api/rooms/KWMP/players")[0]!.body as { name: string; emoji: string };
    expect(body.name).toBe("Olya");
    expect(body.emoji).toMatch(/\p{Extended_Pictographic}/u);
    expect(loadSeat("KWMP", "player")).toMatchObject({ token: "tok-p3", playerId: "p3" });
  });

  it("filters the typed code to the room alphabet and explains a wrong code", async () => {
    mockFetch({ "GET /api/me": apiError(401, "UNAUTHORIZED"), "GET /api/rooms/ZZZZ": apiError(404, "ROOM_NOT_FOUND") });
    renderRoute("/join", "/join", <JoinPage />);
    const input = screen.getByLabelText("Room code");
    await userEvent.setup().type(input, "z1a-zzz");
    expect(input).toHaveValue("ZZZZ");
    expect(await screen.findByText("Check the code on the screen.")).toBeInTheDocument();
  });

  it("explains taken names and rooms that already started", async () => {
    mockFetch({
      "GET /api/me": apiError(401, "UNAUTHORIZED"),
      "GET /api/rooms/KWMP": { status: 200, body: room },
      "POST /api/rooms/KWMP/players": apiError(409, "NAME_TAKEN"),
      "GET /api/rooms/BCDF": {
        status: 200,
        body: { ...room, code: "BCDF", phase: "ANSWERING", joinable: false, audienceOpen: true, audienceKey: "QXZTRPLMNB" },
      },
    });
    renderRoute("/j/KWMP", "/j/:code", <JoinPage />);
    await screen.findByText("Room found ✓");
    const user = userEvent.setup();
    await user.type(screen.getByLabelText("Your name"), "Dima");
    await user.click(screen.getByRole("button", { name: "Join the game" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("That name is taken. Add an initial.");

    const code = screen.getByLabelText("Room code");
    await user.clear(code);
    await user.type(code, "BCDF");
    expect(await screen.findByText("You can join before the next game.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Watch as a viewer" })).toHaveAttribute("href", "/w/QXZTRPLMNB");
    expect(screen.getByRole("button", { name: "Join the game" })).toBeDisabled();
  });

  it("goes straight back to the game when this phone already has a seat", async () => {
    mockFetch({ "GET /api/me": apiError(401, "UNAUTHORIZED"), "GET /api/rooms/KWMP": { status: 200, body: room } });
    saveSeat("KWMP", { token: "tok-old", kind: "player", playerId: "p1" });
    renderRoute("/j/KWMP", "/j/:code", <JoinPage />);
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/play/KWMP"));
  });
});
