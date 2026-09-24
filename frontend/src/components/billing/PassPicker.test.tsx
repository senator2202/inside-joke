import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AccessStatus, CheckoutConfig, Pass } from "../../lib/billing";
import { PaddleUnavailable, setPaddleLoaderForTests, type CheckoutEvent, type PaddleApi } from "../../lib/paddle";
import { apiError, mockFetch } from "../../test/fetchMock";
import { PassPicker } from "./PassPicker";

const config: CheckoutConfig = {
  available: true,
  environment: "sandbox",
  clientToken: "test_tok",
  offers: [
    { product: "PARTY_PASS", priceId: "pri_party", listPriceUsdCents: 299, monthlyGameLimit: null, validityHours: 24 },
    { product: "HOST_PASS", priceId: "pri_host", listPriceUsdCents: 1499, monthlyGameLimit: 15, validityHours: 8760 },
  ],
  email: "ana@example.com",
  customData: { userId: "u1" },
  supportEmail: "help@insidejoke.app",
};

function access(passes: Pass[]): AccessStatus {
  return {
    access: {
      freeGamesEnabled: true,
      freeGameAvailable: false,
      nextFreeGameAt: null,
      passes,
      nextGame: passes.length ? passes[0]!.type : "PAYWALL",
      paywallReason: passes.length ? null : "PAYWALL_FREE_LIMIT",
    },
    drainMode: false,
  };
}

const newPass: Pass = {
  id: "e-new",
  type: "PARTY_PASS",
  endsAt: new Date(Date.now() + 24 * 3600_000).toISOString(),
  monthlyGameLimit: null,
  gamesLeftThisMonth: null,
};

function fakePaddle() {
  const listeners = new Set<(e: CheckoutEvent) => void>();
  const opened: unknown[] = [];
  const api: PaddleApi = {
    openCheckout: (o) => opened.push(o),
    closeCheckout: () => undefined,
    previewPrices: () => Promise.resolve({ pri_party: "€2.79", pri_host: "€13.99" }),
    onEvent: (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
  };
  setPaddleLoaderForTests(() => Promise.resolve(api));
  return { opened, emit: (name: string) => act(() => listeners.forEach((l) => l({ name }))) };
}

describe("PassPicker", () => {
  beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
  afterEach(() => {
    vi.useRealTimers();
    setPaddleLoaderForTests(null);
  });

  it("shows local prices, needs the 18+ confirmation and opens Paddle with the right price", async () => {
    const mock = mockFetch({
      "GET /api/billing/checkout": { status: 200, body: config },
      "GET /api/billing/passes": { status: 200, body: access([]) },
      "POST /api/events": { status: 202 },
    });
    const paddle = fakePaddle();
    render(<PassPicker reason="PAYWALL_FREE_LIMIT" waitingPlayers={4} onDismiss={() => undefined} onActivated={() => undefined} />);
    expect(screen.getByRole("heading", { name: "This week's free game is already played" })).toBeInTheDocument();
    expect(screen.getByText("Your friends are already here: 4 players waiting")).toBeInTheDocument();
    expect(await screen.findByText("€2.79")).toBeInTheDocument();
    expect(screen.getByText("Recommended")).toBeInTheDocument();

    const party = screen.getByRole("button", { name: "Get Party Pass" });
    expect(party).toBeDisabled();
    fireEvent.click(screen.getByLabelText("I’m 18 or older"));
    fireEvent.click(party);
    expect(paddle.opened).toEqual([{ priceId: "pri_party", email: "ana@example.com", customData: { userId: "u1" } }]);
    await waitFor(() =>
      expect(mock.callsTo("POST /api/events")[0]?.body).toMatchObject({ event: "checkout_opened", properties: { product: "PARTY_PASS" } }),
    );
  });

  it("offers only the Party Pass when this month's Host Pass games are used up", async () => {
    mockFetch({
      "GET /api/billing/checkout": { status: 200, body: config },
      "GET /api/billing/passes": { status: 200, body: access([]) },
    });
    fakePaddle();
    render(<PassPicker reason="PAYWALL_MONTHLY_LIMIT" onDismiss={() => undefined} onActivated={() => undefined} />);
    expect(await screen.findByRole("button", { name: "Get Party Pass" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Get Host Pass" })).not.toBeInTheDocument();
  });

  it("waits for the webhook, confirms the pass and starts the game three seconds later", async () => {
    mockFetch({
      "GET /api/billing/checkout": { status: 200, body: config },
      "GET /api/billing/passes": [
        { status: 200, body: access([]) },
        { status: 200, body: access([]) },
        { status: 200, body: access([newPass]) },
      ],
    });
    const paddle = fakePaddle();
    const onActivated = vi.fn();
    render(<PassPicker reason="PAYWALL_FREE_LIMIT" onDismiss={() => undefined} onActivated={onActivated} />);
    fireEvent.click(await screen.findByLabelText("I’m 18 or older"));
    fireEvent.click(screen.getByRole("button", { name: "Get Party Pass" }));
    paddle.emit("checkout.completed");
    expect(screen.getByRole("heading", { name: "Payment went through, activating your pass…" })).toBeInTheDocument();
    await act(() => vi.advanceTimersByTimeAsync(4_100));
    expect(await screen.findByRole("heading", { name: /^Done! Party Pass is active until tomorrow, \d\d:\d\d$/ })).toBeInTheDocument();
    expect(onActivated).not.toHaveBeenCalled();
    await act(() => vi.advanceTimersByTimeAsync(3_000));
    expect(onActivated).toHaveBeenCalledWith(newPass);
  });

  it("says so when the confirmation is slow and keeps waiting", async () => {
    mockFetch({
      "GET /api/billing/checkout": { status: 200, body: config },
      "GET /api/billing/passes": { status: 200, body: access([]) },
    });
    const paddle = fakePaddle();
    render(<PassPicker reason="PAYWALL_FREE_LIMIT" onDismiss={() => undefined} onActivated={() => undefined} />);
    fireEvent.click(await screen.findByLabelText("I’m 18 or older"));
    fireEvent.click(screen.getByRole("button", { name: "Get Host Pass" }));
    paddle.emit("checkout.completed");
    await act(() => vi.advanceTimersByTimeAsync(32_000));
    expect(screen.getByText(/The payment is taking longer than usual. The game will start automatically/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Contact support" })).toHaveAttribute("href", "mailto:help@insidejoke.app");
  });

  it("explains a declined card after the overlay closes", async () => {
    mockFetch({
      "GET /api/billing/checkout": { status: 200, body: config },
      "GET /api/billing/passes": { status: 200, body: access([]) },
    });
    const paddle = fakePaddle();
    render(<PassPicker reason="PAYWALL_FREE_LIMIT" onDismiss={() => undefined} onActivated={() => undefined} />);
    fireEvent.click(await screen.findByLabelText("I’m 18 or older"));
    fireEvent.click(screen.getByRole("button", { name: "Get Party Pass" }));
    paddle.emit("checkout.payment.failed");
    paddle.emit("checkout.closed");
    expect(screen.getByRole("alert")).toHaveTextContent("The payment didn't go through. Try again or use another card.");
    expect(screen.getByRole("button", { name: "Get Party Pass" })).toBeEnabled();
  });

  it("explains an ad blocker, disabled payments and an expired session", async () => {
    mockFetch({
      "GET /api/billing/checkout": { status: 200, body: config },
      "GET /api/billing/passes": { status: 200, body: access([]) },
    });
    setPaddleLoaderForTests(() => Promise.reject(new PaddleUnavailable()));
    const dismiss = vi.fn();
    const { unmount } = render(<PassPicker reason="BUDGET_PAUSED" onDismiss={dismiss} onActivated={() => undefined} />);
    expect(await screen.findByText(/Turn off the ad blocker for this site/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Not now" }));
    expect(dismiss).toHaveBeenCalled();
    unmount();

    mockFetch({
      "GET /api/billing/checkout": { status: 200, body: { ...config, available: false, clientToken: null, offers: [] } },
      "GET /api/billing/passes": { status: 200, body: access([]) },
    });
    const second = render(<PassPicker onDismiss={() => undefined} onActivated={() => undefined} />);
    expect(screen.getByRole("heading", { name: "Get a pass" })).toBeInTheDocument();
    expect(await screen.findByText(/Payments are temporarily unavailable/)).toBeInTheDocument();
    second.unmount();

    mockFetch({ "GET /api/billing/checkout": apiError(401, "UNAUTHORIZED"), "GET /api/billing/passes": apiError(401, "UNAUTHORIZED") });
    render(<PassPicker reason="PAYWALL_FREE_LIMIT" onDismiss={() => undefined} onActivated={() => undefined} />);
    expect(await screen.findByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/login?returnTo=%2F");
  });
});
