import { describe, expect, it } from "vitest";
import { consumeReturnTo, rememberReturnTo, safeReturnTo } from "./returnTo";

describe("safeReturnTo", () => {
  it("keeps same-site paths", () => {
    expect(safeReturnTo("/screen/ABCD")).toBe("/screen/ABCD");
    expect(safeReturnTo("/new?tone=spicy")).toBe("/new?tone=spicy");
  });

  it("rejects anything that could leave the site", () => {
    expect(safeReturnTo("https://evil.example")).toBe("/new");
    expect(safeReturnTo("//evil.example")).toBe("/new");
    expect(safeReturnTo("/\\evil.example")).toBe("/new");
    expect(safeReturnTo("javascript:alert(1)")).toBe("/new");
    expect(safeReturnTo(null)).toBe("/new");
  });
});

describe("remember and consume", () => {
  it("round-trips once and marks the flow as started in this tab", () => {
    rememberReturnTo("/account");
    expect(consumeReturnTo()).toEqual({ path: "/account", startedHere: true });
    expect(consumeReturnTo()).toEqual({ path: "/new", startedHere: false });
  });
});
