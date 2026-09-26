import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { saveSeat } from "../lib/game/storage";
import { setSocketFactoryForTests } from "../lib/game/useGame";
import { apiError, mockFetch } from "../test/fetchMock";
import { fakeSockets, roomState } from "../test/fakeSocket";
import { renderRoute } from "../test/render";
import { NewPartyPage } from "./host/NewPartyPage";
import { LandingPage } from "./host/LandingPage";
import { JoinPage } from "./play/JoinPage";
import { PlayPage } from "./play/PlayPage";

const me = { id: "u1", email: "ana@example.com", displayName: "Ana", role: "HOST", googleLinked: true };

describe("language flows", () => {
  let net: ReturnType<typeof fakeSockets>;
  beforeEach(() => {
    net = fakeSockets();
    setSocketFactoryForTests(net.factory);
  });
  afterEach(() => setSocketFactoryForTests(undefined));

  it("switches the landing page to Russian from the language list and remembers it", async () => {
    mockFetch({
      "GET /api/me": apiError(401, "UNAUTHORIZED"),
      "GET /api/status": { status: 200, body: { drainMode: false, freeGamesEnabled: true } },
      "POST /api/events": { status: 202 },
    });
    renderRoute("/", "/", <LandingPage />);
    const picker = screen.getByLabelText("Language");
    expect(screen.getAllByRole("option").map((o) => o.textContent)).toEqual(["English", "Русский"]);
    fireEvent.change(picker, { target: { value: "ru" } });
    expect(await screen.findByRole("heading", { level: 1 })).toHaveTextContent("Вечеринка, где ведущий знает о вас слишком много");
    expect(screen.getByRole("link", { name: "Создать вечеринку" })).toHaveAttribute("href", "/new");
    expect(screen.getByLabelText("Язык")).toHaveValue("ru");
    expect(localStorage.getItem("ij.pref.lang")).toBe("ru");
  });

  it("creates a room in the chosen game language", async () => {
    const mock = mockFetch({
      "GET /api/me": { status: 200, body: me },
      "GET /api/billing/passes": {
        status: 200,
        body: {
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
        },
      },
      "POST /api/rooms": { status: 201, body: { code: "KWMP", screenToken: "t", joinUrl: "x" } },
    });
    renderRoute("/new", "/new", <NewPartyPage />);
    fireEvent.click(await screen.findByLabelText("Русский"));
    fireEvent.click(screen.getByRole("button", { name: "Create room" }));
    await waitFor(() => expect(mock.callsTo("POST /api/rooms")).toHaveLength(1));
    expect(mock.callsTo("POST /api/rooms")[0]!.body).toMatchObject({ language: "ru" });
  });

  it("a phone in a Russian room gets a Russian interface", async () => {
    mockFetch({ "GET /api/me": apiError(401, "UNAUTHORIZED") });
    saveSeat("KWMP", { token: "tok", kind: "player", playerId: "p1" });
    renderRoute("/play/KWMP", "/play/:code", <PlayPage />);
    const russianLobby = roomState({
      settings: { tone: "CHEEKY", length: "SHORT", mode: "STANDARD", hideCode: false, language: "ru" },
      you: { role: "CAPTAIN", playerId: "p1", name: "Маша", emoji: "🦊" },
    });
    act(() => {
      net.last().open();
      net.last().accept(russianLobby);
    });
    expect(await screen.findByText("Маша, вы в игре! Смотрите на экран")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Все в сборе: начинаем" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Выдать секрет ведущему" })).toBeInTheDocument();
  });

  it("an explicit choice beats the room language", async () => {
    mockFetch({ "GET /api/me": apiError(401, "UNAUTHORIZED") });
    localStorage.setItem("ij.pref.lang", "en");
    saveSeat("KWMP", { token: "tok", kind: "player", playerId: "p1" });
    renderRoute("/play/KWMP", "/play/:code", <PlayPage />);
    act(() => {
      net.last().open();
      net.last().accept(roomState({ settings: { tone: "CHEEKY", length: "SHORT", mode: "STANDARD", hideCode: false, language: "ru" } }));
    });
    expect(await screen.findByText("You\u2019re in, Masha! Look at the screen")).toBeInTheDocument();
  });

  it("the join page switches to the room's language once the code is found", async () => {
    mockFetch({
      "GET /api/me": apiError(401, "UNAUTHORIZED"),
      "GET /api/rooms/KWMP": {
        status: 200,
        body: {
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
          language: "ru",
        },
      },
    });
    renderRoute("/j/KWMP", "/j/:code", <JoinPage />);
    expect(await screen.findByText("Комната найдена ✓")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Войти в игру" })).toBeDisabled();
  });
});
