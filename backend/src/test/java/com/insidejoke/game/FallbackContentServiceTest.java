package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.common.Language;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

class FallbackContentServiceTest {

    private static final Pattern LATIN_WORD = Pattern.compile("\\b[A-Za-z]{4,}\\b");
    private static final Set<String> ALLOWED_LATIN = Set.of("TikTok", "LinkedIn", "Wi-Fi");

    private final FallbackContentService content =
            new FallbackContentService(JsonMapper.builder().build());

    private static PlayerState player(String id, String name, String intake) {
        PlayerState p = new PlayerState(id, name, "🦊", Instant.EPOCH, false);
        p.getIntake()[0] = intake;
        return p;
    }

    private static List<PlayerState> eight() {
        return List.of(
                player("p1", "Masha", "Karaoke"),
                player("p2", "Dima", null),
                player("p3", "Olya", null),
                player("p4", "Ivan", null),
                player("p5", "Kate", null),
                player("p6", "Lev", null),
                player("p7", "Nina", null),
                player("p8", "Oleg", null));
    }

    @ParameterizedTest
    @EnumSource(Language.class)
    void everyLanguageAndToneHasAFullRoundForEightPlayers(Language language) {
        for (Tone tone : Tone.values()) {
            RoundContent round = content.round(language, tone, eight(), Set.of(), new Random(1));
            assertThat(round.duelPrompts()).hasSize(8).doesNotHaveDuplicates();
            assertThat(round.whoOfUsQuestion())
                    .matches(language == Language.RU ? "^(Кто|Кого|У кого) из нас .+" : "^Who of us .+");
            assertThat(round.truth().fakeStatement()).doesNotContain("{name}");
            assertThat(round.source()).isEqualTo("FALLBACK");
            assertThat(content.intakeQuestions(language, tone, new Random(2)))
                    .hasSize(3)
                    .doesNotHaveDuplicates();
        }
        for (String kind : List.of(
                "lobbyGreeting",
                "lobbyWaiting",
                "intakeStart",
                "duelWin",
                "duelLandslide",
                "duelTie",
                "duelNoAnswer",
                "duelNoAnswers",
                "whoReveal",
                "whoNobody",
                "truthRevealTrue",
                "truthRevealFake",
                "skipped",
                "finaleSpeech")) {
            assertThat(content.line(language, kind, new Random(4))).as(kind).isNotBlank();
        }
        for (String kind : List.of("winner", "fastest", "fooler", "detective", "whoMagnet", "votes")) {
            assertThat(content.title(language, kind)).as(kind).isNotBlank();
        }
        assertThat(content.genericTitles(language)).hasSizeGreaterThanOrEqualTo(8);
        for (String key : List.of("blockedAnswer", "noAnswer", "hostPicks")) {
            assertThat(content.label(language, key, Map.of())).isNotBlank();
        }
    }

    @Test
    void russianContentIsRussian() {
        for (Tone tone : Tone.values()) {
            for (int seed = 0; seed < 10; seed++) {
                RoundContent round = content.round(Language.RU, tone, eight(), Set.of(), new Random(seed));
                round.duelPrompts()
                        .forEach(p ->
                                assertThat(latinWords(p.text())).as(p.text()).isEmpty());
                assertThat(latinWords(round.whoOfUsQuestion())).isEmpty();
                assertThat(latinWords(round.truth()
                                .fakeStatement()
                                .replaceAll("Masha|Dima|Olya|Ivan|Kate|Lev|Nina|Oleg", "")))
                        .isEmpty();
            }
        }
        assertThat(content.label(Language.RU, "didntAnswer", Map.of("name", "Маша")))
                .isEqualTo("[Маша: ответа нет]");
        assertThat(content.label(Language.EN, "didntAnswer", Map.of("name", "Masha")))
                .isEqualTo("[Masha didn't answer]");
    }

    private static List<String> latinWords(String text) {
        return LATIN_WORD
                .matcher(text)
                .results()
                .map(MatchResult::group)
                .filter(w -> ALLOWED_LATIN.stream().noneMatch(a -> a.contains(w)))
                .toList();
    }

    @Test
    void recentPromptsAreAvoidedAndTruthComesFromTheIntake() {
        PlayerState masha = player("p1", "Masha", "I collect spoons");
        RoundContent first = content.round(Language.EN, Tone.FAMILY, List.of(masha), Set.of(), new Random(3));
        Set<String> used = Set.copyOf(
                first.duelPrompts().stream().map(RoundContent.DuelPrompt::text).toList());
        RoundContent second = content.round(Language.EN, Tone.FAMILY, List.of(masha), used, new Random(3));
        assertThat(second.duelPrompts()).noneMatch(p -> used.contains(p.text()));
        assertThat(first.truth().truthStatement()).isEqualTo("Masha told me: “I collect spoons”");
        assertThat(first.truth().fakeStatement()).contains("Masha");

        PlayerState ru = player("p1", "Маша", "Собираю ложки");
        RoundContent russian = content.round(Language.RU, Tone.FAMILY, List.of(ru), Set.of(), new Random(3));
        assertThat(russian.truth().truthStatement()).isEqualTo("Маша рассказывает: «Собираю ложки»");
        assertThat(russian.truth().fakeStatement()).contains("Маша");
    }
}
