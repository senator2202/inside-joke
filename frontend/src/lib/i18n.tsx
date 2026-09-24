import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { en, type MessageKey } from "../locales/en";
import { ru } from "../locales/ru";

/**
 * Interface language, per device. A person's explicit choice is remembered; until they choose, the interface follows
 * the room they are in (so a Russian game gets a Russian interface) and otherwise the browser's language.
 */
export type Lang = "en" | "ru";

export const LANGUAGES: { code: Lang; name: string }[] = [
  { code: "en", name: "English" },
  { code: "ru", name: "Русский" },
];

type Dict = Record<MessageKey, string> & Record<string, string>;
const DICTS: Record<Lang, Dict> = { en, ru };
const PREF_KEY = "ij.pref.lang";

/** Keys that have plural forms: `<base>_one`, `<base>_other` (and `_few`, `_many` in Russian). */
export type PluralBase = { [K in MessageKey]: K extends `${infer B}_other` ? B : never }[MessageKey];
export type Params = Record<string, string | number>;

export function isLang(value: unknown): value is Lang {
  return value === "en" || value === "ru";
}

export function detectLanguage(): Lang {
  const langs = typeof navigator === "undefined" ? [] : (navigator.languages ?? [navigator.language]);
  return langs.some((l) => l?.toLowerCase().startsWith("ru")) ? "ru" : "en";
}

function storedLanguage(): Lang | null {
  try {
    const v = localStorage.getItem(PREF_KEY);
    return isLang(v) ? v : null;
  } catch {
    return null;
  }
}

function fill(text: string, params?: Params): string {
  if (!params) return text;
  return text.replace(/\{(\w+)\}/g, (m, name: string) => (name in params ? String(params[name]) : m));
}

export function translate(lang: Lang, key: MessageKey, params?: Params): string {
  return fill(DICTS[lang][key] ?? en[key], params);
}

export function translatePlural(lang: Lang, base: PluralBase, count: number, params?: Params): string {
  const category = new Intl.PluralRules(lang).select(count);
  const dict = DICTS[lang];
  const text = dict[`${base}_${category}`] ?? dict[`${base}_other`] ?? (en as Dict)[`${base}_other`] ?? base;
  return fill(text, { count, ...params });
}

export interface I18n {
  lang: Lang;
  /** BCP 47 locale for dates and numbers. */
  locale: string;
  t: (key: MessageKey, params?: Params) => string;
  tp: (base: PluralBase, count: number, params?: Params) => string;
  /** "1st" / "1-е": a place in the standings. */
  place: (n: number) => string;
  /** "1st" / "1-м": the same, after "on the … place". */
  placeIn: (n: number) => string;
  /** Player-facing text for a server error code; the server's own message only in English, else a generic line. */
  error: (code: string | undefined, serverMessage?: string) => string;
  setLang: (lang: Lang) => void;
  /** Game screens call this with the room language; it applies only while the person hasn't chosen one. */
  followRoom: (lang: Lang | null) => void;
}

function englishOrdinal(n: number): string {
  const s = ["th", "st", "nd", "rd"];
  const v = n % 100;
  return n + (s[(v - 20) % 10] ?? s[v] ?? s[0]!);
}

export function makeI18n(
  lang: Lang,
  setLang: (l: Lang) => void = () => undefined,
  followRoom: (l: Lang | null) => void = () => undefined,
): I18n {
  return {
    lang,
    locale: lang === "ru" ? "ru-RU" : "en-GB",
    t: (key, params) => translate(lang, key, params),
    tp: (base, count, params) => translatePlural(lang, base, count, params),
    place: (n) => (lang === "ru" ? `${n}-е` : englishOrdinal(n)),
    placeIn: (n) => (lang === "ru" ? `${n}-м` : englishOrdinal(n)),
    error: (code, serverMessage) => {
      const key = `error.${code ?? ""}`;
      if (code && key in DICTS[lang]) return DICTS[lang][key]!;
      if (lang === "en" && serverMessage) return serverMessage;
      return translate(lang, "error.generic");
    },
    setLang,
    followRoom,
  };
}

const ENGLISH = makeI18n("en");
const I18nContext = createContext<I18n>(ENGLISH);

export function I18nProvider({ children }: { children: ReactNode }) {
  const [explicit, setExplicit] = useState<Lang | null>(storedLanguage);
  const [room, setRoom] = useState<Lang | null>(null);
  const lang: Lang = explicit ?? room ?? detectLanguage();

  const setLang = useCallback((next: Lang) => {
    setExplicit(next);
    try {
      localStorage.setItem(PREF_KEY, next);
    } catch {
      /* private mode: the choice lasts for this page */
    }
  }, []);

  useEffect(() => {
    document.documentElement.lang = lang;
  }, [lang]);

  const value = useMemo(() => makeI18n(lang, setLang, setRoom), [lang, setLang]);
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18n {
  return useContext(I18nContext);
}

/** While mounted, lets the room language drive the interface (unless the person chose a language). */
export function useFollowRoomLanguage(roomLanguage: string | null | undefined): void {
  const { followRoom } = useI18n();
  const lang = isLang(roomLanguage) ? roomLanguage : null;
  useEffect(() => {
    followRoom(lang);
  }, [followRoom, lang]);
  useEffect(() => () => followRoom(null), [followRoom]);
}
