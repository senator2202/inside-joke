import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { mockFetch } from "../../test/fetchMock";
import { renderRoute } from "../../test/render";
import { AdminPage } from "./AdminPage";
import type { AiCallRow, AiStatus, Page } from "./adminApi";
import { readAiQuery, usd } from "./AiTab";

const admin = { id: "a1", email: "boss@example.com", displayName: null, role: "ADMIN", googleLinked: false };

const failing: AiStatus = {
  keyConfigured: true,
  model: "claude-haiku-4-5",
  lastCall: { at: "2026-09-23T10:00:00Z", purpose: "ROUND_GEN", model: "claude-haiku-4-5", outcome: "OK", latencyMs: 1400, error: null },
  lastFailure: {
    at: "2026-09-23T10:05:00Z",
    purpose: "MODERATION",
    model: "claude-haiku-4-5",
    outcome: "ERROR",
    latencyMs: 120,
    error: "Anthropic HTTP 400: invalid_request_error: Your credit balance is too low to access the Anthropic API.",
  },
  rateLimits: {
    capturedAt: "2026-09-23T10:05:00Z",
    httpStatus: 400,
    retryAfter: null,
    tokens: null,
    requests: { limit: 50, remaining: 49, reset: "2026-09-23T10:06:00Z" },
    inputTokens: { limit: 50000, remaining: 48000, reset: null },
    outputTokens: { limit: 10000, remaining: 9900, reset: null },
  },
};

function call(overrides: Partial<AiCallRow>): AiCallRow {
  return {
    id: 1,
    createdAt: "2026-09-23T10:05:00Z",
    purpose: "MODERATION",
    provider: "anthropic",
    model: "claude-haiku-4-5",
    promptVersion: "moderation.v2",
    outcome: "ERROR",
    inputTokens: null,
    outputTokens: null,
    ttsChars: null,
    costMicros: 0,
    latencyMs: 120,
    freeGame: false,
    gameSessionId: null,
    roomCode: null,
    error: "Anthropic HTTP 400: invalid_request_error: Your credit balance is too low to access the Anthropic API.",
    ...overrides,
  };
}

const calls: Page<AiCallRow> = {
  items: [
    call({}),
    call({
      id: 2,
      purpose: "ROUND_GEN",
      outcome: "OK",
      inputTokens: 900,
      outputTokens: 300,
      costMicros: 2400,
      latencyMs: 1400,
      roomCode: "KWMP",
      error: null,
      promptVersion: "round_gen.v2",
    }),
    call({ id: 3, purpose: "TTS", provider: "tts", model: "tts-1", outcome: "OK", ttsChars: 64, costMicros: 960, error: null }),
  ],
  page: 0,
  size: 25,
  totalItems: 40,
  totalPages: 2,
};

function setup(status: AiStatus = failing, path = "/admin?tab=ai") {
  const mock = mockFetch({
    "GET /api/me": { status: 200, body: admin },
    "GET /api/status": { status: 200, body: { drainMode: false, freeGamesEnabled: true, version: "0.6.0" } },
    "GET /api/admin/ai/status": { status: 200, body: status },
    "GET /api/admin/ai/calls": { status: 200, body: calls },
  });
  renderRoute(path, "/admin", <AdminPage />);
  return mock;
}

const lastCalls = (mock: ReturnType<typeof mockFetch>) => mock.callsTo("GET /api/admin/ai/calls").at(-1)!.path;

describe("AI tab", () => {
  it("shows the key, the model, the latest failure and the live rate limits", async () => {
    setup();
    const status = await screen.findByRole("region", { name: "Status" });
    expect(await within(status).findByText("Set")).toBeInTheDocument();
    expect(within(status).getAllByText("claude-haiku-4-5")[0]).toBeInTheDocument();
    expect(within(status).getByText("The latest call to Anthropic failed")).toBeInTheDocument();
    expect(within(status).getAllByText(/credit balance is too low/).length).toBeGreaterThan(0);
    expect(within(status).getByLabelText("Requests: 49 of 50 left")).toBeInTheDocument();
    expect(within(status).getByText("48,000 / 50,000 left")).toBeInTheDocument();
    expect(within(status).queryByText(/^Tokens$/)).not.toBeInTheDocument();
  });

  it("says plainly when the key is missing", async () => {
    setup({ keyConfigured: false, model: "claude-haiku-4-5", lastCall: null, lastFailure: null, rateLimits: null });
    expect(await screen.findByText("ANTHROPIC_API_KEY is not set")).toBeInTheDocument();
    expect(screen.getByText("Not set")).toBeInTheDocument();
    expect(screen.getByText("No calls yet")).toBeInTheDocument();
    expect(screen.getByText(/Rate limits appear after the first call/)).toBeInTheDocument();
  });

  it("lists calls with their cost, game and error, and filters to failures through the URL", async () => {
    const mock = setup();
    const table = await screen.findByRole("table", { name: "AI calls" });
    await within(table).findByText("KWMP");
    expect(within(table).getByText("900 / 300")).toBeInTheDocument();
    expect(within(table).getByText("$0.0024")).toBeInTheDocument();
    expect(within(table).getByText("64 chars")).toBeInTheDocument();
    expect(within(table).getByText(/credit balance is too low/)).toBeInTheDocument();
    expect(screen.getByText("Showing 1–3 of 40")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Outcome"), { target: { value: "FAILURES" } });
    await waitFor(() => expect(lastCalls(mock)).toContain("outcome=FAILURES"));
    expect(screen.getByTestId("location")).toHaveTextContent("outcome=FAILURES");
    fireEvent.change(screen.getByLabelText("Purpose"), { target: { value: "MODERATION" } });
    await waitFor(() => expect(lastCalls(mock)).toContain("purpose=MODERATION"));
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await waitFor(() => expect(lastCalls(mock)).toContain("page=1"));
    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    await waitFor(() => expect(lastCalls(mock)).not.toContain("outcome="));
  });

  it("reads only valid filters from the URL and formats tiny costs", () => {
    expect(readAiQuery(new URLSearchParams("purpose=tts&outcome=failures&page=2&size=50&from=2026-09-01"))).toEqual({
      from: "2026-09-01",
      to: undefined,
      purpose: "TTS",
      outcome: "FAILURES",
      page: 2,
      size: 50,
    });
    expect(readAiQuery(new URLSearchParams("purpose=x&outcome=y&page=-1&size=7"))).toMatchObject({
      purpose: undefined,
      outcome: undefined,
      page: 0,
      size: 25,
    });
    expect(usd(2400)).toBe("$0.0024");
    expect(usd(1_234_567)).toBe("$1.23");
    expect(usd(0)).toBe("$0.00");
  });
});
