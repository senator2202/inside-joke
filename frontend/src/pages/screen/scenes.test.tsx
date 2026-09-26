import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { RoomView } from "../../lib/game/types";
import { roomState } from "../../test/fakeSocket";
import { FinaleScene, LobbyScene, VotingScene, type SceneProps } from "./scenes";

function props(state: RoomView, owner = true): SceneProps & { sent: { type: string; data: unknown }[] } {
  const sent: { type: string; data: unknown }[] = [];
  return { state, offset: 0, owner, speaking: false, sent, send: (type, data = {}) => sent.push({ type, data }) };
}

const owner = { role: "OWNER_SCREEN" as const };

describe("Lobby (S1)", () => {
  it("shows the code, the empty seats and how many players are missing", () => {
    const p = props(roomState({ you: owner }));
    render(<LobbyScene {...p} />);
    expect(screen.getByLabelText("Room code KWMP")).toHaveTextContent("KWMP");
    expect(screen.getByRole("heading", { name: "Need 1 more player" })).toBeInTheDocument();
    expect(screen.getAllByRole("listitem")).toHaveLength(8);
    fireEvent.click(screen.getByRole("button", { name: "Remove Dima" }));
    expect(p.sent).toEqual([{ type: "player.kick", data: { playerId: "p2" } }]);
  });

  it("hides the code for streamers until the owner asks", () => {
    vi.useFakeTimers();
    const state = roomState({
      you: owner,
      settings: { tone: "FAMILY", length: "SHORT", mode: "STREAMER", hideCode: true },
      lobby: {
        joinUrl: "http://localhost/j/KWMP",
        audienceUrl: "http://localhost/w/QXZTRPLMNB",
        audienceCount: 12,
        minPlayers: 3,
        maxPlayers: 8,
      },
    });
    render(<LobbyScene {...props(state)} />);
    expect(screen.queryByLabelText("Room code KWMP")).not.toBeInTheDocument();
    expect(screen.getByText("👀 12")).toBeInTheDocument();
    expect(screen.getByText("localhost/w/QXZTRPLMNB")).toBeInTheDocument();
    expect(document.body.textContent).not.toContain("KWMP");
    expect(screen.getByText(/Go full screen so the address bar/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Show code for 10 seconds" }));
    expect(screen.getByLabelText("Room code KWMP")).toBeInTheDocument();
    vi.useRealTimers();
  });

  it("lets the owner of an admin's room add test bots, and marks them", () => {
    const lobby = { joinUrl: "http://localhost/j/KWMP", audienceCount: 0, minPlayers: 3, maxPlayers: 8, botsAllowed: true };
    const base = roomState({ you: owner, lobby });
    const state = {
      ...base,
      players: [...(base.players ?? []), { ...base.players![0]!, id: "p9", name: "Robo Rita", captain: false, bot: true }],
    };
    const p = props(state);
    render(<LobbyScene {...p} />);
    expect(screen.getByText("bot")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "🤖 Add a bot" }));
    expect(p.sent).toEqual([{ type: "bot.add", data: {} }]);
  });

  it("offers no bots in an ordinary room or on a copy of the screen", () => {
    render(<LobbyScene {...props(roomState({ you: owner }))} />);
    expect(screen.queryByRole("button", { name: "🤖 Add a bot" })).not.toBeInTheDocument();
    const lobby = { audienceCount: 0, minPlayers: 3, maxPlayers: 8, botsAllowed: true };
    render(<LobbyScene {...props(roomState({ you: { role: "SCREEN" as const }, lobby }), false)} />);
    expect(screen.queryByRole("button", { name: "🤖 Add a bot" })).not.toBeInTheDocument();
  });

  it("asks before switching an open room to Spicy", () => {
    const p = props(roomState({ you: owner }));
    render(<LobbyScene {...p} />);
    fireEvent.click(screen.getByRole("button", { name: "Change" }));
    fireEvent.click(screen.getByRole("button", { name: "Spicy 18+" }));
    expect(p.sent).toEqual([]);
    fireEvent.click(screen.getByRole("button", { name: "Yes" }));
    expect(p.sent).toEqual([{ type: "game.settings", data: { tone: "SPICY", adultsConfirmed: true } }]);
  });
});

describe("Duel reveal (S6)", () => {
  it("names the authors, counts votes and calls a wipeout", () => {
    const state = roomState({
      you: owner,
      phase: "REVEAL",
      round: {
        n: 1,
        of: 5,
        kind: "ANSWER_DUEL",
        duelIndex: 1,
        duelCount: 3,
        prompt: "Worst superpower?",
        landslide: true,
        options: [
          { id: "A", text: "Pigeons", authorId: "p1", votes: 4, points: 1100, winner: true },
          { id: "B", text: "Half-invisibility", authorId: "p2", votes: 0, points: 0, winner: false },
        ],
      },
      host: { lineId: "l1", text: "Masha, the pigeons have spoken.", skipped: false },
    });
    render(<VotingScene {...props(state)} />);
    expect(screen.getByText("🥊 Funny answer · Duel 2 of 3")).toBeInTheDocument();
    expect(screen.getByText("Wipeout!")).toBeInTheDocument();
    expect(screen.getByText("+1100")).toBeInTheDocument();
    expect(screen.getByText("4 votes · 100%")).toBeInTheDocument();
    expect(screen.getByText("Masha, the pigeons have spoken.")).toBeInTheDocument();
  });

  it("keeps authors hidden while voting", () => {
    const state = roomState({
      you: owner,
      phase: "VOTING",
      deadline: Date.now() + 20_000,
      round: {
        n: 1,
        of: 5,
        kind: "ANSWER_DUEL",
        duelIndex: 0,
        duelCount: 3,
        prompt: "Worst superpower?",
        voters: ["p2"],
        options: [
          { id: "A", text: "Pigeons" },
          { id: "B", text: "Half-invisibility" },
        ],
      },
    });
    render(<VotingScene {...props(state)} />);
    expect(screen.queryByText("Masha")).not.toBeInTheDocument();
    expect(screen.getByRole("timer")).toBeInTheDocument();
  });
});

describe("Finale (S8)", () => {
  it("crowns the winner and lets the owner play again", () => {
    const standings = roomState().players!.map((p, i) => ({ ...p, score: 900 - i * 300, rank: i + 1 }));
    const p = props(
      roomState({
        you: owner,
        phase: "FINALE",
        finale: {
          ready: true,
          standings,
          winnerIds: ["p1"],
          titles: { p1: "Queen of pigeons", p2: "Silent assassin" },
          answerOfNight: "Pigeons",
          answerOfNightPrompt: "Worst superpower?",
          answerOfNightAuthorId: "p1",
          speech: "What a night.",
        },
      }),
    );
    render(<FinaleScene {...p} />);
    expect(screen.getByRole("heading", { name: "Masha" })).toBeInTheDocument();
    expect(screen.getAllByText("Queen of pigeons").length).toBeGreaterThan(0);
    expect(screen.getByText("Answer of the night · Masha")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Play again" }));
    expect(p.sent).toEqual([{ type: "game.again", data: {} }]);
  });
});
