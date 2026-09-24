package com.insidejoke.common;

import java.util.Locale;
import java.util.Optional;

/**
 * Language of a room: the AI host writes and speaks in it, and the fallback content, host lines and screen labels come
 * in it. The interface language is chosen per device on the client.
 */
public enum Language {
    EN("en", "English", "Who of us"),
    RU("ru", "Russian", "Кто из нас");

    private final String code;
    private final String englishName;
    private final String whoOfUs;

    Language(String code, String englishName, String whoOfUs) {
        this.code = code;
        this.englishName = englishName;
        this.whoOfUs = whoOfUs;
    }

    /** ISO 639-1 code used on the wire and in the database. */
    public String code() {
        return code;
    }

    /** Name used inside prompts ("Write in Russian"). */
    public String englishName() {
        return englishName;
    }

    /** How a "who of us" question starts in this language. */
    public String whoOfUs() {
        return whoOfUs;
    }

    public static Optional<Language> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String c = code.trim().toLowerCase(Locale.ROOT);
        for (Language l : values()) {
            if (l.code.equals(c)) {
                return Optional.of(l);
            }
        }
        return Optional.empty();
    }
}
