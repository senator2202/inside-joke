import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { mockFetch } from "../../test/fetchMock";
import { renderRoute } from "../../test/render";
import { AdminPage } from "./AdminPage";
import type { NotAppliedRow, Page } from "./adminApi";

const admin = { id: "a1", email: "boss@example.com", displayName: null, role: "ADMIN", googleLinked: false };

const partial: NotAppliedRow = {
  eventId: "evt_1",
  eventType: "adjustment.updated",
  receivedAt: "2026-09-22T10:00:00Z",
  action: "refund",
  status: "approved",
  txnId: "txn_1",
  reason: "PARTIAL",
  detail: "Partial refund: 1.00 USD of 2.99 USD paid. The pass stays active.",
  purchaseId: "p1",
  product: "PARTY_PASS",
  amountMinor: 299,
  currency: "USD",
  purchaseStatus: "COMPLETED",
  userId: "u1",
  userEmail: "alice@example.com",
  passId: "e1",
  passRevokedAt: null,
  passEndsAt: "2026-09-23T10:00:00Z",
  passHeld: true,
};
const page = (items: NotAppliedRow[]): Page<NotAppliedRow> => ({ items, page: 0, size: 25, totalItems: items.length, totalPages: 1 });

describe("Payment issues", () => {
  it("explains unapplied refunds and chargebacks and lets the admin revoke the pass", async () => {
    const mock = mockFetch({
      "GET /api/me": { status: 200, body: admin },
      "GET /api/admin/webhooks/failed": { status: 200, body: [] },
      "GET /api/admin/webhooks/not-applied": [
        { status: 200, body: page([partial]) },
        { status: 200, body: page([]) },
        {
          status: 200,
          body: page([
            {
              ...partial,
              eventId: "evt_2",
              reason: "CHARGEBACK_REVERSED",
              detail: "Reversed.",
              passHeld: false,
              passRevokedAt: "2026-09-22T11:00:00Z",
            },
          ]),
        },
      ],
      "POST /api/admin/passes/e1/revoke": { status: 200, body: { revoked: true } },
    });
    renderRoute("/admin?tab=payments", "/admin", <AdminPage />);
    const table = await screen.findByRole("table", { name: "Refunds and chargebacks not applied" });
    expect(within(table).getByText("Partial refund")).toBeInTheDocument();
    expect(within(table).getByText(/1.00 USD of 2.99 USD/)).toBeInTheDocument();
    expect(within(table).getByText("Still held")).toBeInTheDocument();
    expect(mock.callsTo("GET /api/admin/webhooks/not-applied")[0]!.path).toContain("stillHeld=true");

    fireEvent.click(within(table).getByRole("button", { name: "Revoke the pass of alice@example.com" }));
    await waitFor(() => expect(mock.callsTo("POST /api/admin/passes/e1/revoke")).toHaveLength(1));
    expect(await screen.findByText(/Nothing waiting/)).toBeInTheDocument();

    fireEvent.click(screen.getByLabelText("Only where the customer still has the pass"));
    expect(await screen.findByText("Chargeback reversed")).toBeInTheDocument();
    expect(mock.callsTo("GET /api/admin/webhooks/not-applied").at(-1)!.path).toContain("stillHeld=false");
    expect(screen.getByTestId("location")).toHaveTextContent("held=all");
    expect(screen.getByText("Nothing stuck. 🎉")).toBeInTheDocument();
  });
});
