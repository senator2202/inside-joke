import { describe, expectTypeOf, it } from "vitest";
import type * as Contract from "./contract.gen";
import type { Me } from "../auth";
import type { AccessStatus, CheckoutConfig, Offer, Pass } from "../billing";
import type { Assignment, FinaleView, Option, PlayerView, RoomStatus, RoomView, RoundView, You } from "../game/types";
import type {
  AdminUser,
  AiCallRow,
  AiCallSummary,
  AiStatus,
  AiWindow,
  FailedWebhook,
  Live,
  Metrics,
  NotAppliedRow,
  Page,
  PassRow,
  PassSummary,
} from "../../pages/admin/adminApi";

/**
 * The frontend's own types must have exactly the fields of the backend contract (contract.gen.ts, generated from the Java
 * records). A renamed, removed or added field on either side fails the type check, so the build fails.
 */
type SameFields<A, B> = [Exclude<keyof A, keyof B>, Exclude<keyof B, keyof A>] extends [never, never] ? true : false;

describe("frontend types match the backend contract", () => {
  it("WebSocket protocol", () => {
    expectTypeOf<SameFields<RoomView, Contract.RoomStateDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<PlayerView, Contract.PlayerDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<You, Contract.YouDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<Assignment, Contract.AssignmentDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<RoundView, Contract.RoundDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<Option, Contract.OptionDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<FinaleView, Contract.FinaleDto>>().toEqualTypeOf<true>();
  });

  it("HTTP API for players and hosts", () => {
    expectTypeOf<SameFields<RoomStatus, Contract.RoomStatusDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<Me, Contract.ProfileDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<AccessStatus, Contract.AccessDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<Pass, Contract.PassStatusDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<Offer, Contract.OfferDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<CheckoutConfig, Contract.CheckoutConfigDto>>().toEqualTypeOf<true>();
  });

  it("admin API", () => {
    expectTypeOf<SameFields<Metrics, Contract.AdminMetricsDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<Live, Contract.LiveStatsDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<AdminUser, Contract.AdminUserDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<Page<unknown>, Contract.PageDto<unknown>>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<PassRow, Contract.PassLogEntryDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<PassSummary, Contract.PassSummaryDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<NotAppliedRow, Contract.NotAppliedEventDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<FailedWebhook, Contract.FailedWebhookDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<AiStatus, Contract.AiStatusDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<AiWindow, Contract.RateLimitWindowDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<AiCallSummary, Contract.AiCallSummaryDto>>().toEqualTypeOf<true>();
    expectTypeOf<SameFields<AiCallRow, Contract.AiCallDto>>().toEqualTypeOf<true>();
  });
});
