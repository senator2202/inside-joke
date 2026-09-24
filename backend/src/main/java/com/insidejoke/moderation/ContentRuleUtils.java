package com.insidejoke.moderation;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Cheap, deterministic checks that run on every player text before anything else (blueprint 10.2):
 * contact details, links and addresses never reach the AI or the screen, and a short list of topics the host
 * never jokes about is refused for secrets. The model-based check handles everything subtler.
 */
public final class ContentRuleUtils {

    private record Rule(String category, Pattern pattern) {}

    private static final List<Rule> ALWAYS = List.of(
            new Rule("contact_info", Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]{2,}")),
            new Rule("contact_info", Pattern.compile("(?:\\+|\\b)\\d(?:[\\s().-]*\\d){6,}")),
            new Rule(
                    "link",
                    Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+"
                            + "|\\b[a-z0-9-]+\\.(?:com|net|org|io|ru|de|uk|me|co|app|ly|gg|tv)\\b")),
            new Rule("contact_info", Pattern.compile("(?i)(?<![\\w])@[a-z0-9_.]{3,}")),
            new Rule(
                    "address",
                    Pattern.compile("(?i)\\b\\d{1,5}\\s+(?:[a-z]+\\s){0,3}"
                            + "(?:street|st\\.|avenue|ave\\.|road|rd\\.|lane|"
                            + "boulevard|blvd|drive|strasse|straße|ulitsa|ul\\.)\\b")),
            // Russian: Cyrillic domains, and addresses written street first ("ул. Ленина, д. 5").
            new Rule("link", Pattern.compile("(?iu)(?<![\\p{L}\\d-])[\\p{L}\\d-]+\\.(?:рф|su)(?!\\p{L})")),
            new Rule(
                    "address",
                    Pattern.compile("(?iu)(?<!\\p{L})(?:ул\\.|улица|проспект|пр-т|переулок|пер\\.|бульвар|шоссе)"
                            + "\\s*[\\p{L}-]+(?:\\s+[\\p{L}-]+)?[\\s,]*(?:д\\.|дом)?\\s*\\d{1,4}")));

    /** Topics the host never uses in secrets or intake answers (user flow P4). */
    private static final Rule SENSITIVE = new Rule(
            "sensitive_topic",
            Pattern.compile(
                    "(?i)\\b(?:cancer|chemo|hiv|aids|std|stds|pregnan\\w*|miscarriage|abortion|diagnos\\w*|depress\\w*|suicid\\w*|"
                            + "self[- ]?harm|anorexi\\w*|bulimi\\w*|disorder|disabilit\\w*|rehab|overweight|obese|ugly|"
                            + "gay|lesbian|bisexual|transgender|closeted|came out|coming out|orientation)\\b"));

    /**
     * The same topics in Russian. Word starts are matched by stem; short words that are prefixes of harmless ones
     * ("гей" in "геймер", "спид" in "спидометр") are matched whole, and a word must start at a letter boundary
     * (so the "вич" of a patronymic never counts).
     */
    private static final Rule SENSITIVE_RU = new Rule(
            "sensitive_topic",
            Pattern.compile(
                    "(?iu)(?<!\\p{L})(?:(?:онколог|химиотерап|беремен|выкидыш|аборт|диагноз|депресс|суицид|самоубий|самоповрежд|"
                            + "анорекси|булими|расстройств|инвалид|реабилитац|ожирен|урод|"
                            + "лесби|бисексуал|трансгендер|кам(?:ин|инг)[- ]?аут|"
                            + "ориентаци|нетрадиционн|психиатр|психушк|наркозавис)\\p{L}*|(?:гей|геи|геев|вич|спид)(?!\\p{L}))"));

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\n]]");
    private static final Pattern HAS_LETTER_OR_DIGIT = Pattern.compile("[\\p{L}\\p{N}]");

    private ContentRuleUtils() {}

    /** Checks an answer shown on the shared screen. Returns the violated category, if any. */
    public static Optional<String> checkAnswer(String text) {
        return first(ALWAYS, text);
    }

    /** Checks a secret or intake answer: stricter, also refuses sensitive topics. */
    public static Optional<String> checkPersonal(String text) {
        Optional<String> always = first(ALWAYS, text);
        if (always.isPresent()) {
            return always;
        }
        boolean sensitive = SENSITIVE.pattern().matcher(text).find()
                || SENSITIVE_RU.pattern().matcher(text).find();
        return sensitive ? Optional.of(SENSITIVE.category()) : Optional.empty();
    }

    /** Display names: short, printable, no contact details or links. */
    public static boolean acceptableName(String name) {
        return HAS_LETTER_OR_DIGIT.matcher(name).find()
                && !CONTROL.matcher(name).find()
                && first(ALWAYS, name).isEmpty();
    }

    /** Collapses whitespace and strips invisible characters that could smuggle formatting. */
    public static String clean(String text) {
        return CONTROL.matcher(text == null ? "" : text)
                .replaceAll("")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static Optional<String> first(List<Rule> rules, String text) {
        for (Rule rule : rules) {
            if (rule.pattern().matcher(text).find()) {
                return Optional.of(rule.category());
            }
        }
        return Optional.empty();
    }
}
