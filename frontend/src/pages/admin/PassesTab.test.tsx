import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { mockFetch } from "../../test/fetchMock";
import { renderRoute } from "../../test/render";
import { AdminPage } from "./AdminPage";
import type { Page, PassRow, PassSummary } from "./adminApi";
import { readPassQuery } from "./PassesTab";

const admin = { id: "a1", email: "boss@example.com", displayName: null, role: "ADMIN", googleLinked: false };

function row(overrides: Partial<PassRow>): PassRow {
  return {
    id: "e1",
    type: "PARTY_PASS",
    source: "PURCHASE",
    status: "ACTIVE",
    userId: "u1",
    userEmail: "alice@example.com",
    userDeleted: false,
    createdAt: "2026-09-20T18:00:00Z",
    startsAt: "2026-09-20T18:00:00Z",
    endsAt: "2026-09-21T18:00:00Z",
    monthlyGameLimit: null,
    gamesPlayed: 2,
    txnId: "txn_1",
    amountMinor: 299,
    currency: "USD",
    refundedAt: null,
    refundKind: null,
    grantedByEmail: null,
    revokedAt: null,
    revokeReason: null,
    revokedByEmail: null,
    ...overrides,
  };
}

const page: Page<PassRow> = {
  items: [
    row({}),
    row({
      id: "e2",
      type: "HOST_PASS",
      source: "GRANT",
      userEmail: "carol@example.com",
      amountMinor: null,
      currency: null,
      txnId: null,
      grantedByEmail: "boss@example.com",
      monthlyGameLimit: 15,
    }),
    row({
      id: "e3",
      status: "REFUNDED",
      userEmail: "erin@example.com",
      refundKind: "CHARGEBACK",
      refundedAt: "2026-09-21T10:00:00Z",
      revokedAt: "2026-09-21T10:00:00Z",
      revokeReason: "CHARGEBACK",
    }),
  ],
  page: 0,
  size: 25,
  totalItems: 60,
  totalPages: 3,
};

const summary: PassSummary = {
  sold: 6,
  granted: 1,
  refunded: 1,
  chargebacks: 1,
  refundRate: 0.333,
  activeNow: 2,
  revenue: [{ currency: "USD", grossMinor: 2695, refundedMinor: 299, netMinor: 2396 }],
  byType: {
    PARTY_PASS: { sold: 4, granted: 0, refunded: 0, chargebacks: 1 },
    HOST_PASS: { sold: 2, granted: 1, refunded: 1, chargebacks: 0 },
  },
};

function setup(path = "/admin?tab=passes") {
  const mock = mockFetch({
    "GET /api/me": { status: 200, body: admin },
    "GET /api/admin/passes": { status: 200, body: page },
    "GET /api/admin/passes/summary": { status: 200, body: summary },
    "POST /api/admin/passes/e1/revoke": { status: 200, body: { revoked: true } },
  });
  renderRoute(path, "/admin", <AdminPage />);
  return mock;
}

const lastListCall = (mock: ReturnType<typeof mockFetch>) => mock.callsTo("GET /api/admin/passes").at(-1)!.path;

describe("Passes tab", () => {
  it("shows the totals and every kind of pass", async () => {
    setup();
    expect(await screen.findByText("alice@example.com")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Passes" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByText("$23.96")).toBeInTheDocument();
    expect(screen.getByText("1 refunds · 1 chargebacks · 33.3% of sold")).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Passes" });
    expect(within(table).getByText("by boss@example.com")).toBeInTheDocument();
    expect(within(table).getByText("Chargeback")).toBeInTheDocument();
    expect(within(table).getByText(/· 15\/month/)).toBeInTheDocument();
    expect(screen.getByText("Showing 1–3 of 60")).toBeInTheDocument();
  });

  it("sends filters to the server, keeps them in the URL and starts again from page one", async () => {
    const mock = setup("/admin?tab=passes&page=2");
    await screen.findByText("alice@example.com");
    expect(lastListCall(mock)).toContain("page=2");
    fireEvent.change(screen.getByLabelText("Status"), { target: { value: "REFUNDED" } });
    await waitFor(() => expect(lastListCall(mock)).toContain("status=REFUNDED"));
    expect(lastListCall(mock)).toContain("page=0");
    expect(screen.getByTestId("location")).toHaveTextContent("status=REFUNDED");
    expect(mock.callsTo("GET /api/admin/passes/summary").at(-1)!.path).not.toContain("status");

    fireEvent.change(screen.getByLabelText("Customer email"), { target: { value: "  erin " } });
    await waitFor(() => expect(lastListCall(mock)).toContain("email=erin"));

    fireEvent.click(screen.getByRole("button", { name: "Clear filters" }));
    await waitFor(() => expect(lastListCall(mock)).not.toContain("status="));
  });

  it("sorts by a column and pages through results", async () => {
    const mock = setup();
    await screen.findByText("alice@example.com");
    const paid = screen.getByRole("columnheader", { name: /Paid/ });
    expect(paid).toHaveAttribute("aria-sort", "none");
    fireEvent.click(within(paid).getByRole("button"));
    await waitFor(() => expect(lastListCall(mock)).toContain("sort=amount&dir=desc"));
    expect(screen.getByRole("columnheader", { name: /Paid/ })).toHaveAttribute("aria-sort", "descending");
    fireEvent.click(within(screen.getByRole("columnheader", { name: /Paid/ })).getByRole("button"));
    await waitFor(() => expect(lastListCall(mock)).toContain("dir=asc"));

    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await waitFor(() => expect(lastListCall(mock)).toContain("page=1"));
    fireEvent.change(screen.getByLabelText("Per page"), { target: { value: "100" } });
    await waitFor(() => expect(lastListCall(mock)).toContain("size=100"));
    expect(lastListCall(mock)).toContain("page=0");
  });

  it("revokes an active pass and reloads", async () => {
    const mock = setup();
    await screen.findByText("alice@example.com");
    const before = mock.callsTo("GET /api/admin/passes").length;
    fireEvent.click(screen.getByRole("button", { name: "Revoke Party Pass of alice@example.com" }));
    await waitFor(() => expect(mock.callsTo("POST /api/admin/passes/e1/revoke")).toHaveLength(1));
    await waitFor(() => expect(mock.callsTo("GET /api/admin/passes").length).toBeGreaterThan(before));
    expect(screen.queryByRole("button", { name: /Revoke Party Pass of erin/ })).not.toBeInTheDocument();
  });

  it("reads only valid state from the URL", () => {
    expect(readPassQuery(new URLSearchParams("status=refunded&type=HOST_PASS&size=100&page=3&sort=amount&dir=asc"))).toEqual({
      type: "HOST_PASS",
      source: undefined,
      status: "REFUNDED",
      from: undefined,
      to: undefined,
      email: undefined,
      sort: "amount",
      dir: "asc",
      page: 3,
      size: 100,
    });
    expect(readPassQuery(new URLSearchParams("status=nope&size=7&page=-2&sort=id;drop&from=yesterday"))).toMatchObject({
      status: undefined,
      size: 25,
      page: 0,
      sort: "created",
      from: undefined,
    });
  });
});
