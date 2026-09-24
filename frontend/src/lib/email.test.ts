import { describe, expect, it } from "vitest";
import { isValidEmail } from "./email";

describe("isValidEmail", () => {
  it("accepts ordinary addresses, trimming spaces", () => {
    expect(isValidEmail("ana@example.com")).toBe(true);
    expect(isValidEmail("  ana.b+party@mail.example.co.uk ")).toBe(true);
  });

  it("rejects malformed addresses", () => {
    for (const bad of ["", "ana", "ana@", "ana@example", "ana @example.com", "@example.com", "ana@example.c"]) {
      expect(isValidEmail(bad), bad).toBe(false);
    }
  });
});
