import { describe, expect, it } from "vitest";
import { apiError, mockFetch } from "../test/fetchMock";
import { api, isApiError, type ApiError } from "./api";

describe("api", () => {
  it("fetches a CSRF token once and sends it on mutating requests", async () => {
    const mock = mockFetch({ "POST /api/things": { status: 200, body: { ok: true } } });
    await api("/api/things", { method: "POST", body: { a: 1 } });
    await api("/api/things", { method: "POST", body: { a: 2 } });
    expect(mock.callsTo("GET /api/auth/csrf")).toHaveLength(1);
    const posts = mock.callsTo("POST /api/things");
    expect(posts.map((c) => c.headers["X-XSRF-TOKEN"])).toEqual(["test-csrf", "test-csrf"]);
    expect(posts[1]?.body).toEqual({ a: 2 });
  });

  it("does not send a CSRF header on GET", async () => {
    const mock = mockFetch({ "GET /api/me": { status: 200, body: { id: "u1" } } });
    await api("/api/me");
    expect(mock.callsTo("GET /api/auth/csrf")).toHaveLength(0);
    expect(mock.calls[0]?.headers["X-XSRF-TOKEN"]).toBeUndefined();
  });

  it("turns the error envelope into an ApiError with code and details", async () => {
    mockFetch({ "POST /api/auth/email-code/verify": apiError(400, "CODE_INVALID", { attemptsLeft: 3 }) });
    const error = await api("/api/auth/email-code/verify", { method: "POST", body: {} }).catch((e: unknown) => e);
    expect(isApiError(error, "CODE_INVALID")).toBe(true);
    expect((error as ApiError).status).toBe(400);
    expect((error as ApiError).details.attemptsLeft).toBe(3);
  });

  it("returns undefined for 204", async () => {
    mockFetch({ "POST /api/auth/logout": { status: 204 } });
    await expect(api("/api/auth/logout", { method: "POST" })).resolves.toBeUndefined();
  });
});
