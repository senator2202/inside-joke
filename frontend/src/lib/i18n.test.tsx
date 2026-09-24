import { act, render, renderHook, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { I18nProvider, detectLanguage, makeI18n, translatePlural, useFollowRoomLanguage, useI18n } from "./i18n";
import { formatPassEnd } from "./billing";

const wrapper = ({ children }: { children: ReactNode }) => <I18nProvider>{children}</I18nProvider>;

describe("i18n", () => {
  afterEach(() => vi.restoreAllMocks());

  it("declines Russian plurals", () => {
    const votes = [1, 2, 5, 11, 21, 22, 25].map((n) => translatePlural("ru", "votes.count", n));
    expect(votes).toEqual(["1 голос", "2 голоса", "5 голосов", "11 голосов", "21 голос", "22 голоса", "25 голосов"]);
    expect(translatePlural("en", "votes.count", 1)).toBe("1 vote");
    expect(translatePlural("en", "votes.count", 3)).toBe("3 votes");
  });

  it("formats places, errors and dates per language", () => {
    const ru = makeI18n("ru");
    const en = makeI18n("en");
    expect(ru.tp("phone.rank", 900, { rank: ru.placeIn(2) })).toBe("Вы на 2-м месте, у вас 900 очков");
    expect(ru.tp("phone.place", 1, { place: ru.place(1) })).toBe("1-е место · 1 очко");
    expect(en.tp("phone.place", 900, { place: en.place(2) })).toBe("2nd place · 900 points");
    expect(ru.error("ROOM_FULL")).toBe("В этой комнате уже 8 игроков.");
    expect(en.error("SOMETHING_NEW", "Server says hi")).toBe("Server says hi");
    expect(ru.error("SOMETHING_NEW", "Server says hi")).toBe("Что-то пошло не так. Попробуйте снова.");
    const tomorrow = new Date(2026, 8, 22, 21, 40).toISOString();
    expect(formatPassEnd(tomorrow, new Date(2026, 8, 21, 20, 0), "ru")).toBe("завтра, 21:40");
  });

  it("detects Russian browsers", () => {
    vi.spyOn(navigator, "languages", "get").mockReturnValue(["ru-RU", "en-US"]);
    expect(detectLanguage()).toBe("ru");
    vi.spyOn(navigator, "languages", "get").mockReturnValue(["de-DE", "en-US"]);
    expect(detectLanguage()).toBe("en");
  });

  it("remembers an explicit choice and marks the document language", () => {
    const { result } = renderHook(() => useI18n(), { wrapper });
    expect(result.current.lang).toBe("en");
    act(() => result.current.setLang("ru"));
    expect(result.current.t("layout.signIn")).toBe("Войти");
    expect(localStorage.getItem("ij.pref.lang")).toBe("ru");
    expect(document.documentElement.lang).toBe("ru");
  });

  it("follows the room language until the person chooses one", () => {
    function Probe({ room }: { room: string }) {
      useFollowRoomLanguage(room);
      const { t, setLang } = useI18n();
      return (
        <button type="button" onClick={() => setLang("en")}>
          {t("layout.signIn")}
        </button>
      );
    }
    const { rerender, unmount } = render(<Probe room="ru" />, { wrapper });
    expect(screen.getByRole("button")).toHaveTextContent("Войти");
    act(() => screen.getByRole("button").click());
    rerender(<Probe room="ru" />);
    expect(screen.getByRole("button")).toHaveTextContent("Sign in");
    unmount();
  });

  it("works without a provider, in English", () => {
    const { result } = renderHook(() => useI18n());
    expect(result.current.t("common.playAgain")).toBe("Play again");
  });
});
