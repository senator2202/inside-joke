import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { loadDraft } from "../../lib/game/storage";
import { GameError, type RoomView } from "../../lib/game/types";
import { roomState } from "../../test/fakeSocket";
import { AnswerPhone, IntakePhone, SecretSheet, VotePhone } from "./scenes";

function requests(reply: (type: string, data: unknown) => Promise<Record<string, unknown>> = () => Promise.resolve({})) {
  const calls: { type: string; data: unknown }[] = [];
  const request = vi.fn((type: string, data?: unknown) => {
    calls.push({ type, data });
    return reply(type, data);
  });
  return { calls, request };
}

const answering = (overrides: Partial<RoomView["you"]> = {}): RoomView =>
  roomState({
    phase: "ANSWERING",
    deadline: Date.now() + 60_000,
    you: {
      role: "PLAYER",
      playerId: "p1",
      name: "Masha",
      emoji: "🦊",
      assignments: [
        { duelId: "d0", prompt: "Worst superpower?" },
        { duelId: "d1", prompt: "A terrible pet name?" },
      ],
      ...overrides,
    },
  });

describe("AnswerPhone", () => {
  afterEach(() => vi.useRealTimers());

  it("walks through both prompts and then waits", async () => {
    const { calls, request } = requests();
    render(<AnswerPhone state={answering()} offset={0} request={request} onSecret={() => undefined} />);
    expect(screen.getByText("Prompt 1 of 2")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Worst superpower?"), { target: { value: "  Talking to pigeons " } });
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByLabelText("A terrible pet name?")).toHaveValue("");
    fireEvent.change(screen.getByLabelText("A terrible pet name?"), { target: { value: "Mr. Bills" } });
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByText("Answers sent ✓ Waiting for the others")).toBeInTheDocument();
    expect(calls).toEqual([
      { type: "answer.submit", data: { duelId: "d0", text: "Talking to pigeons" } },
      { type: "answer.submit", data: { duelId: "d1", text: "Mr. Bills" } },
    ]);
  });

  it("sends a typed draft by itself when time runs out", async () => {
    vi.useFakeTimers();
    const { calls, request } = requests();
    render(<AnswerPhone state={answering()} offset={0} request={request} onSecret={() => undefined} />);
    fireEvent.change(screen.getByLabelText("Worst superpower?"), { target: { value: "Half-invisibility" } });
    expect(loadDraft("d0")).toBe("Half-invisibility");
    await act(() => vi.advanceTimersByTimeAsync(58_600));
    expect(calls).toEqual([{ type: "answer.submit", data: { duelId: "d0", text: "Half-invisibility" } }]);
  });

  it("keeps the text when the host refuses an answer", async () => {
    const { request } = requests(() => Promise.reject(new GameError("MODERATION_BLOCKED", "no")));
    render(<AnswerPhone state={answering()} offset={0} request={request} onSecret={() => undefined} />);
    fireEvent.change(screen.getByLabelText("Worst superpower?"), { target: { value: "see bit.ly/x" } });
    fireEvent.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The host won't show that answer. Try something else.");
    expect(screen.getByLabelText("Worst superpower?")).toHaveValue("see bit.ly/x");
  });
});

describe("VotePhone", () => {
  const voting = (you: Partial<RoomView["you"]>, round: Partial<NonNullable<RoomView["round"]>> = {}) =>
    roomState({
      phase: "VOTING",
      deadline: Date.now() + 20_000,
      round: {
        n: 1,
        of: 5,
        kind: "ANSWER_DUEL",
        duelIndex: 0,
        duelCount: 3,
        prompt: "Worst superpower?",
        options: [
          { id: "A", text: "Pigeons" },
          { id: "B", text: "Half-invisibility" },
        ],
        ...round,
      },
      you: { role: "PLAYER", playerId: "p1", name: "Masha", emoji: "🦊", ...you },
    });

  it("tells duel players and the subject of a fact to sit tight", () => {
    const { request } = requests();
    const { rerender } = render(<VotePhone state={voting({ inDuel: true })} offset={0} request={request} />);
    expect(screen.getByText("It\u2019s your duel. Watch the screen and hold on 😬")).toBeInTheDocument();
    rerender(
      <VotePhone
        state={voting({ subject: true }, { kind: "TRUTH_OR_AI", statement: "Masha collects spoons" })}
        offset={0}
        request={request}
      />,
    );
    expect(screen.getByText("It\u2019s about you. Keep a poker face 😐")).toBeInTheDocument();
  });

  it("votes with one tap", async () => {
    const { calls, request } = requests();
    render(<VotePhone state={voting({ canVote: true })} offset={0} request={request} />);
    fireEvent.click(screen.getByRole("button", { name: /Half-invisibility/ }));
    expect(await screen.findByText("Vote accepted")).toBeInTheDocument();
    expect(calls).toEqual([{ type: "vote.submit", data: { optionId: "B" } }]);
  });
});

describe("IntakePhone", () => {
  it("goes back to the refused question and explains why", async () => {
    const { calls, request } = requests((type) =>
      type === "intake.submit" && calls.length === 1
        ? Promise.reject(new GameError("MODERATION_BLOCKED", "no", { rejected: [1] }))
        : Promise.resolve({}),
    );
    const state = roomState({
      phase: "INTAKE",
      deadline: Date.now() + 90_000,
      intake: { questions: ["Favourite food?", "Hidden talent?", "Karaoke song?"], done: 0, total: 3 },
      you: { role: "PLAYER", playerId: "p1", intakeNeeded: true },
    });
    render(<IntakePhone state={state} offset={0} request={request} />);
    for (const [label, value] of [
      ["Favourite food?", "Pizza"],
      ["Hidden talent?", "www.me.com"],
      ["Karaoke song?", "ABBA"],
    ]) {
      fireEvent.change(screen.getByLabelText(label!), { target: { value } });
      fireEvent.click(screen.getByRole("button", { name: label === "Karaoke song?" ? "Done" : "Next" }));
    }
    expect(await screen.findByRole("alert")).toHaveTextContent("The host can't use this answer. Try another one.");
    expect(screen.getByLabelText("Hidden talent?")).toHaveValue("www.me.com");
    fireEvent.change(screen.getByLabelText("Hidden talent?"), { target: { value: "Juggling" } });
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(calls[1]).toEqual({ type: "intake.submit", data: { answers: ["Pizza", "Juggling", "ABBA"] } });
  });
});

describe("SecretSheet", () => {
  it("needs a person for a secret about someone and reports refusals", async () => {
    const { calls, request } = requests(() => Promise.reject(new GameError("MODERATION_BLOCKED", "no")));
    const state = roomState({ you: { role: "PLAYER", playerId: "p1", name: "Masha", secretsLeft: 7 } });
    render(<SecretSheet state={state} request={request} onClose={() => undefined} onAccepted={() => undefined} />);
    expect(screen.getByText("Secrets left: 7 of 10")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("radio", { name: "About someone" }));
    fireEvent.change(screen.getByLabelText("Secret"), { target: { value: "Got lost in IKEA" } });
    const give = screen.getByRole("button", { name: "Give it to the host 🤫" });
    expect(give).toBeDisabled();
    expect(screen.queryByRole("radio", { name: /Masha/ })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("radio", { name: /Dima/ }));
    fireEvent.click(give);
    expect(await screen.findByRole("alert")).toHaveTextContent("The host doesn't touch that topic.");
    expect(calls).toEqual([{ type: "dossier.add", data: { aboutPlayerId: "p2", text: "Got lost in IKEA" } }]);
  });

  it("stops at the limit", () => {
    const { request } = requests();
    const state = roomState({ you: { role: "PLAYER", playerId: "p1", secretsLeft: 0 } });
    render(<SecretSheet state={state} request={request} onClose={() => undefined} onAccepted={() => undefined} />);
    fireEvent.change(screen.getByLabelText("Secret"), { target: { value: "One more" } });
    expect(screen.getByText("That's the secret limit for this game")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Give it to the host 🤫" })).toBeDisabled();
  });
});
