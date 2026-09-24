import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { apiError, mockFetch } from "../../test/fetchMock";
import { renderRoute } from "../../test/render";
import { rememberReturnTo } from "../../lib/returnTo";
import { AuthCallbackPage } from "./AuthCallbackPage";

const me = { id: "u1", email: "ana@example.com", displayName: null, role: "HOST", googleLinked: false };

describe("AuthCallbackPage", () => {
  it("verifies the link once, hides the token and continues where sign-in started", async () => {
    rememberReturnTo("/screen/KWMP");
    const mock = mockFetch({
      "POST /api/auth/magic-link/verify": { status: 200, body: { ok: true } },
      "GET /api/me": [apiError(401, "UNAUTHORIZED"), { status: 200, body: me }],
    });
    renderRoute("/auth/callback?token=tok-1", "/auth/callback", <AuthCallbackPage />);
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/screen/KWMP"));
    expect(mock.callsTo("POST /api/auth/magic-link/verify").map((c) => c.body)).toEqual([{ token: "tok-1" }]);
  });

  it("on another device, suggests entering the code there but allows continuing", async () => {
    mockFetch({
      "POST /api/auth/magic-link/verify": { status: 200, body: { ok: true } },
      "GET /api/me": { status: 200, body: me },
    });
    renderRoute("/auth/callback?token=tok-2", "/auth/callback", <AuthCallbackPage />);
    expect(await screen.findByRole("heading", { name: "You're signed in on this device" })).toBeInTheDocument();
    expect(screen.getByTestId("location")).toHaveTextContent("/auth/callback");
    await userEvent.setup().click(screen.getByRole("link", { name: "Continue on this device" }));
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/new"));
  });

  it("explains a used or expired link", async () => {
    mockFetch({
      "POST /api/auth/magic-link/verify": apiError(400, "LINK_INVALID"),
      "GET /api/me": apiError(401, "UNAUTHORIZED"),
    });
    renderRoute("/auth/callback?token=tok-3", "/auth/callback", <AuthCallbackPage />);
    expect(await screen.findByRole("heading", { name: "This link no longer works" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Get a new code" })).toHaveAttribute("href", "/login");
  });

  it("retries after a network failure without losing the token", async () => {
    rememberReturnTo("/new");
    let calls = 0;
    mockFetch({
      "POST /api/auth/magic-link/verify": () => (++calls === 1 ? apiError(503, "INTERNAL") : { status: 200, body: { ok: true } }),
      "GET /api/me": { status: 200, body: me },
    });
    renderRoute("/auth/callback?token=tok-4", "/auth/callback", <AuthCallbackPage />);
    await userEvent.setup().click(await screen.findByRole("button", { name: "Try again" }));
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/new"));
    expect(calls).toBe(2);
  });

  it("finishes a Google sign-in", async () => {
    rememberReturnTo("/account");
    mockFetch({ "GET /api/me": { status: 200, body: me } });
    renderRoute("/auth/callback?provider=google", "/auth/callback", <AuthCallbackPage />);
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/account"));
  });
});
