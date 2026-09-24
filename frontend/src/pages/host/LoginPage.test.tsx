import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { apiError, mockFetch } from "../../test/fetchMock";
import { renderRoute } from "../../test/render";
import { LoginPage } from "./LoginPage";

const me = { id: "u1", email: "ana@example.com", displayName: null, role: "HOST", googleLinked: false };

function setup(extra: Parameters<typeof mockFetch>[0] = {}) {
  return mockFetch({
    "GET /api/auth/config": { status: 200, body: { googleEnabled: true } },
    "GET /api/me": [apiError(401, "UNAUTHORIZED"), { status: 200, body: me }],
    "POST /api/auth/magic-link": { status: 202, body: { sent: true, resendAfterSeconds: 60 } },
    ...extra,
  });
}

async function requestCode(email = "ana@example.com") {
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText("Email"), email);
  await user.click(screen.getByRole("button", { name: "Get a code" }));
  return user;
}

describe("LoginPage", () => {
  it("offers Google first when it is configured", async () => {
    setup();
    renderRoute("/login", "/login", <LoginPage />);
    expect(await screen.findByRole("button", { name: /Continue with Google/ })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Sign in to host a party" })).toBeInTheDocument();
  });

  it("hides Google when the server has no client configured", async () => {
    setup({ "GET /api/auth/config": { status: 200, body: { googleEnabled: false } } });
    renderRoute("/login", "/login", <LoginPage />);
    await screen.findByRole("button", { name: "Get a code" });
    await waitFor(() => expect(screen.queryByRole("button", { name: /Google/ })).not.toBeInTheDocument());
  });

  it("validates the email before calling the server", async () => {
    const mock = setup();
    renderRoute("/login", "/login", <LoginPage />);
    await requestCode("not-an-email");
    expect(await screen.findByText("Enter an email like name@example.com.")).toBeInTheDocument();
    expect(mock.callsTo("POST /api/auth/magic-link")).toHaveLength(0);
  });

  it("signs in with the emailed code and returns to returnTo", async () => {
    const mock = setup({
      "POST /api/auth/email-code/verify": [apiError(400, "CODE_INVALID", { attemptsLeft: 4 }), { status: 200, body: { ok: true } }],
    });
    renderRoute("/login?returnTo=%2Fscreen%2FKWMP", "/login", <LoginPage />);
    const user = await requestCode();

    expect(await screen.findByRole("heading", { name: "Check your email" })).toBeInTheDocument();
    expect(screen.getByText("ana@example.com")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Send again in \d+s/ })).toBeDisabled();

    await user.click(screen.getAllByRole("textbox")[0]!);
    await user.keyboard("111111");
    expect(await screen.findByText("That code doesn't match. Attempts left: 4.")).toBeInTheDocument();

    await user.click(screen.getAllByRole("textbox")[0]!);
    await user.keyboard("482913");
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/screen/KWMP"));
    expect(mock.callsTo("POST /api/auth/email-code/verify").map((c) => c.body)).toEqual([
      { email: "ana@example.com", code: "111111" },
      { email: "ana@example.com", code: "482913" },
    ]);
  });

  it("locks the code after too many wrong tries and leaves only a resend", async () => {
    setup({ "POST /api/auth/email-code/verify": apiError(400, "CODE_LOCKED") });
    renderRoute("/login", "/login", <LoginPage />);
    const user = await requestCode();
    await user.click((await screen.findAllByRole("textbox"))[0]!);
    await user.keyboard("000000");
    expect(await screen.findByText("Too many wrong codes. Send a new one.")).toBeInTheDocument();
    for (const cell of screen.getAllByRole("textbox")) expect(cell).toBeDisabled();
  });

  it("explains the rate limit and suggests Google", async () => {
    setup({ "POST /api/auth/magic-link": apiError(429, "RATE_LIMITED") });
    renderRoute("/login", "/login", <LoginPage />);
    await requestCode();
    expect(await screen.findByText("Too many attempts. Try again in an hour or continue with Google.")).toBeInTheDocument();
  });

  it("explains a failed email send", async () => {
    setup({ "POST /api/auth/magic-link": apiError(502, "EMAIL_SEND_FAILED") });
    renderRoute("/login", "/login", <LoginPage />);
    await requestCode();
    expect(await screen.findByText("We couldn't send the email. Continue with Google instead.")).toBeInTheDocument();
  });

  it("shows the Google error passed back by the server", async () => {
    setup();
    renderRoute("/login?error=google", "/login", <LoginPage />);
    expect(await screen.findByText(/Google sign-in didn't finish/)).toBeInTheDocument();
  });

  it("sends a signed-in host straight to returnTo", async () => {
    mockFetch({
      "GET /api/auth/config": { status: 200, body: { googleEnabled: true } },
      "GET /api/me": { status: 200, body: me },
    });
    renderRoute("/login?returnTo=%2Faccount", "/login", <LoginPage />);
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/account"));
  });

  it("change email goes back to the form and keeps what was typed", async () => {
    setup();
    renderRoute("/login", "/login", <LoginPage />);
    const user = await requestCode();
    await user.click(await screen.findByRole("button", { name: "Change email" }));
    expect(screen.getByLabelText("Email")).toHaveValue("ana@example.com");
  });
});
