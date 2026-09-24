import { describe, expect, it } from "vitest";
import { formatPassEnd, listPrice } from "./billing";

describe("billing helpers", () => {
  const now = new Date(2026, 8, 21, 20, 0);

  it("formats when a pass ends relative to now", () => {
    expect(formatPassEnd(new Date(2026, 8, 21, 23, 5).toISOString(), now)).toBe("today, 23:05");
    expect(formatPassEnd(new Date(2026, 8, 22, 21, 40).toISOString(), now)).toBe("tomorrow, 21:40");
    expect(formatPassEnd(new Date(2027, 9, 21, 20, 0).toISOString(), now)).toBe("21 Oct 2027");
  });

  it("formats list prices", () => {
    expect(listPrice(299)).toBe("$2.99");
    expect(listPrice(1499)).toBe("$14.99");
  });
});
