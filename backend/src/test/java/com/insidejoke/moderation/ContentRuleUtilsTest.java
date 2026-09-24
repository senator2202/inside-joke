package com.insidejoke.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContentRuleUtilsTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "write me at masha@example.com",
                "call +7 (912) 345-67-89",
                "text 07946 095 818",
                "see https://example.org/x",
                "go to www.site.io",
                "insidejoke.app is cool",
                "follow @masha_rocks",
                "she lives at 12 Baker Street"
            })
    void contactDetailsAndLinksAreNeverShown(String text) {
        assertThat(ContentRuleUtils.checkAnswer(text)).isPresent();
        assertThat(ContentRuleUtils.checkPersonal(text)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "A penguin in a tuxedo",
                "Dima got lost in IKEA for 3 hours",
                "Her 2 cats and 1 dog",
                "Year 2019 was wild",
                "I ate 12 donuts"
            })
    void ordinaryTextPasses(String text) {
        assertThat(ContentRuleUtils.checkAnswer(text)).isEmpty();
        assertThat(ContentRuleUtils.checkPersonal(text)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "She is pregnant",
                "He came out last year",
                "diagnosed with flu",
                "went to rehab",
                "is overweight"
            })
    void sensitiveTopicsAreRefusedForSecretsOnly(String text) {
        assertThat(ContentRuleUtils.checkPersonal(text)).contains("sensitive_topic");
        assertThat(ContentRuleUtils.checkAnswer(text)).isEmpty();
    }

    @Test
    void namesAndCleaning() {
        assertThat(ContentRuleUtils.acceptableName("Masha")).isTrue();
        assertThat(ContentRuleUtils.acceptableName("Лёша")).isTrue();
        assertThat(ContentRuleUtils.acceptableName("🦊🦊")).isFalse();
        assertThat(ContentRuleUtils.acceptableName("x.com")).isFalse();
        assertThat(ContentRuleUtils.clean("  a\u200b b\n\n c\u0007 ")).isEqualTo("a b c");
        assertThat(ContentRuleUtils.clean(null)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Она беременна",
                "Ему поставили диагноз",
                "У него депрессия",
                "Лечится в реабилитационном центре",
                "Он гей",
                "Сделала каминг-аут",
                "Лежал в психушке",
                "Её сексуальная ориентация",
                "Он считает себя уродом"
            })
    void russianSensitiveTopicsAreRefusedForSecrets(String text) {
        assertThat(ContentRuleUtils.checkPersonal(text)).contains("sensitive_topic");
        assertThat(ContentRuleUtils.checkAnswer(text)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Дима — заядлый геймер",
                "Сергей Иванович любит рыбалку",
                "Смотрит на спидометр каждые пять секунд",
                "Носит толстовку бывшего",
                "Разводит раков на даче",
                "Собрал урожай огурцов"
            })
    void harmlessRussianTextPasses(String text) {
        assertThat(ContentRuleUtils.checkPersonal(text)).as(text).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "пиши мне на пример.рф",
                "живёт на ул. Ленина, д. 5",
                "проспект Мира 12",
                "звони +7 912 345-67-89"
            })
    void russianContactsAndAddressesAreNeverShown(String text) {
        assertThat(ContentRuleUtils.checkAnswer(text)).as(text).isPresent();
    }
}
