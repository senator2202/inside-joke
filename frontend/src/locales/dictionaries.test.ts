import { describe, expect, it } from "vitest";
import { en } from "./en";
import { ru } from "./ru";

const placeholders = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();
const enKeys = Object.keys(en) as (keyof typeof en)[];

describe("dictionaries", () => {
  it("Russian has every English key, none empty", () => {
    const missing = enKeys.filter((k) => !(k in ru));
    expect(missing).toEqual([]);
    const empty = Object.entries(ru).filter(([k, v]) => v === "" && k !== "landing.promiseAfter");
    expect(empty).toEqual([]);
  });

  it("uses the same placeholders in both languages", () => {
    const mismatched = enKeys.filter((k) => placeholders(en[k]).join() !== placeholders(ru[k]).join());
    expect(mismatched).toEqual([]);
  });

  it("gives Russian all four plural forms and no stray keys", () => {
    const bases = enKeys.filter((k) => k.endsWith("_other")).map((k) => k.slice(0, -"_other".length));
    for (const base of bases) {
      for (const form of ["one", "few", "many", "other"]) {
        expect(ru[`${base}_${form}`], `${base}_${form}`).toBeTruthy();
        expect(placeholders(ru[`${base}_${form}`]!)).toEqual(placeholders(en[`${base}_other` as keyof typeof en]));
      }
    }
    const allowedExtra = new Set(bases.flatMap((b) => [`${b}_few`, `${b}_many`]));
    const stray = Object.keys(ru).filter((k) => !(k in en) && !allowedExtra.has(k));
    expect(stray).toEqual([]);
  });

  it("really is Russian", () => {
    const latinOnly = Object.entries(ru).filter(([, v]) => v.length > 12 && !/[а-яё]/i.test(v));
    expect(latinOnly.map(([k]) => k)).toEqual([]);
  });
});
