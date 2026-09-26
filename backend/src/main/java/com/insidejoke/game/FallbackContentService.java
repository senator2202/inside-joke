package com.insidejoke.game;

import com.insidejoke.common.Language;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Prewritten content from resources/content/fallback.json, used whenever the AI is late, invalid or over budget. */
@Service
public class FallbackContentService {

    /** One language's worth of content. */
    private static final class Pack {
        final Map<Tone, List<String>> intake = new EnumMap<>(Tone.class);
        final Map<Tone, List<String>> duelPrompts = new EnumMap<>(Tone.class);
        final Map<Tone, List<String>> whoOfUs = new EnumMap<>(Tone.class);
        final Map<Tone, List<String>> fakeFacts = new EnumMap<>(Tone.class);
        final Map<String, List<String>> lines = new HashMap<>();
        final Map<String, String> titles = new HashMap<>();
        final List<String> genericTitles = new ArrayList<>();
        final Map<String, String> labels = new HashMap<>();
        final List<String> botNames = new ArrayList<>();
        final List<String> botAnswers = new ArrayList<>();
    }

    private final Map<Language, Pack> packs = new EnumMap<>(Language.class);

    public FallbackContentService(JsonMapper json) {
        for (Language language : Language.values()) {
            String file =
                    language == Language.EN ? "content/fallback.json" : "content/fallback." + language.code() + ".json";
            packs.put(language, load(json, file));
        }
    }

    private static Pack load(JsonMapper json, String file) {
        JsonNode root;
        try (InputStream in = new ClassPathResource(file).getInputStream()) {
            root = json.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(file + " is missing", e);
        }
        Pack pack = new Pack();
        for (Tone tone : Tone.values()) {
            pack.intake.put(tone, strings(root.path("intake").path(tone.name())));
            pack.duelPrompts.put(tone, strings(root.path("duelPrompts").path(tone.name())));
            pack.whoOfUs.put(tone, strings(root.path("whoOfUs").path(tone.name())));
            pack.fakeFacts.put(tone, strings(root.path("fakeFacts").path(tone.name())));
            if (pack.intake.get(tone).size() < 3
                    || pack.duelPrompts.get(tone).size() < 16
                    || pack.whoOfUs.get(tone).isEmpty()
                    || pack.fakeFacts.get(tone).isEmpty()) {
                throw new IllegalStateException(file + " has too little content for tone " + tone);
            }
        }
        for (Map.Entry<String, JsonNode> e : root.path("lines").properties()) {
            pack.lines.put(e.getKey(), strings(e.getValue()));
        }
        for (Map.Entry<String, JsonNode> e : root.path("titles").properties()) {
            if (e.getValue().isArray()) {
                pack.genericTitles.addAll(strings(e.getValue()));
            } else {
                pack.titles.put(e.getKey(), e.getValue().asString());
            }
        }
        for (Map.Entry<String, JsonNode> e : root.path("labels").properties()) {
            pack.labels.put(e.getKey(), e.getValue().asString());
        }
        pack.botNames.addAll(strings(root.path("bots").path("names")));
        pack.botAnswers.addAll(strings(root.path("bots").path("answers")));
        if (pack.botNames.size() < 8 || pack.botAnswers.size() < 3) {
            throw new IllegalStateException(file + " needs 8 bot names and 3 bot answers");
        }
        return pack;
    }

    private Pack pack(Language language) {
        return packs.get(language == null ? Language.EN : language);
    }

    public List<String> intakeQuestions(Language language, Tone tone, RandomGenerator random) {
        List<String> all = new ArrayList<>(pack(language).intake.get(tone));
        Collections.shuffle(all, random);
        return List.copyOf(all.subList(0, 3));
    }

    /** A full round's worth of prewritten content, personalized only by player names. */
    public RoundContent round(
            Language language, Tone tone, List<PlayerState> players, Set<String> avoid, RandomGenerator random) {
        Pack pack = pack(language);
        List<String> prompts = new ArrayList<>(pack.duelPrompts.get(tone));
        Collections.shuffle(prompts, random);
        prompts.sort((a, b) -> Boolean.compare(avoid.contains(a), avoid.contains(b)));
        List<RoundContent.DuelPrompt> duel = prompts.subList(0, Math.clamp(players.size(), 3, prompts.size())).stream()
                .map(p -> new RoundContent.DuelPrompt(p, null))
                .toList();
        List<String> who = new ArrayList<>(pack.whoOfUs.get(tone));
        who.removeIf(avoid::contains);
        if (who.isEmpty()) {
            who.addAll(pack.whoOfUs.get(tone));
        }
        String question = who.get(random.nextInt(who.size()));
        RoundContent.Truth truth = null;
        if (!players.isEmpty()) {
            PlayerState subject = players.get(random.nextInt(players.size()));
            String fake = pick(pack.fakeFacts.get(tone), random).replace("{name}", subject.getName());
            String real = realStatement(pack, subject, random);
            truth = new RoundContent.Truth(
                    subject.getId(),
                    real,
                    fake,
                    line(language, "truthRevealTrue", random).replace("{name}", subject.getName()),
                    line(language, "truthRevealFake", random).replace("{name}", subject.getName()));
        }
        return new RoundContent(duel, question, truth, "FALLBACK");
    }

    private static String realStatement(Pack pack, PlayerState subject, RandomGenerator random) {
        List<String> answered = new ArrayList<>();
        for (String a : subject.getIntake()) {
            if (a != null && !a.isBlank()) {
                answered.add(a);
            }
        }
        if (answered.isEmpty()) {
            return null;
        }
        return pack.labels
                .get("toldMe")
                .replace("{name}", subject.getName())
                .replace("{text}", answered.get(random.nextInt(answered.size())));
    }

    public String line(Language language, String kind, RandomGenerator random) {
        List<String> options = pack(language).lines.getOrDefault(kind, List.of());
        if (options.isEmpty()) {
            throw new IllegalArgumentException("No fallback lines of kind " + kind);
        }
        return pick(options, random);
    }

    /**
     * A name for a new test bot (roadmap R34) that no player in the room has, ignoring case; {@code "Bot N"} once the
     * prewritten ones are all taken.
     */
    public String botName(Language language, Set<String> taken, RandomGenerator random) {
        Set<String> used = new HashSet<>();
        taken.forEach(n -> used.add(n.toLowerCase(Locale.ROOT)));
        List<String> free = pack(language).botNames.stream()
                .filter(n -> !used.contains(n.toLowerCase(Locale.ROOT)))
                .toList();
        if (!free.isEmpty()) {
            return pick(free, random);
        }
        int n = 1;
        while (used.contains(("bot " + n).toLowerCase(Locale.ROOT))) {
            n++;
        }
        return "Bot " + n;
    }

    /** A prewritten answer a test bot gives to an intake question or a duel prompt. */
    public String botAnswer(Language language, RandomGenerator random) {
        return pick(pack(language).botAnswers, random);
    }

    /** Short fixed texts shown on screens, such as "[No answer]". */
    public String label(Language language, String key, Map<String, String> values) {
        String text = pack(language).labels.get(key);
        if (text == null) {
            throw new IllegalArgumentException("No label " + key);
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            text = text.replace("{" + e.getKey() + "}", e.getValue());
        }
        return text;
    }

    public String title(Language language, String kind) {
        return pack(language).titles.get(kind);
    }

    public List<String> genericTitles(Language language) {
        return List.copyOf(pack(language).genericTitles);
    }

    private static String pick(List<String> list, RandomGenerator random) {
        return list.get(random.nextInt(list.size()));
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asString()));
        return out;
    }
}
