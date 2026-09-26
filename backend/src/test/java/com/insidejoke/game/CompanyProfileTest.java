package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import com.insidejoke.common.Language;
import com.insidejoke.game.dto.RoomSettingsDto;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Who came to the party (roadmap R39): what it allows, what the host is told and who sees the owner's line. */
class CompanyProfileTest {

    private final GameHarness h = new GameHarness();

    private static final RoomSettings COLLEAGUES = new RoomSettings(
            Tone.CHEEKY, GameLength.SHORT, RoomMode.STREAMER, false, Language.EN, Company.COLLEAGUES, "Sales team");

    @Test
    void aGroupThatMustStayCleanGetsFamilySafeContentAndNoSpicy() {
        assertThat(COLLEAGUES.contentTone()).isEqualTo(Tone.FAMILY);
        assertThat(new RoomSettings(Tone.CHEEKY, GameLength.SHORT, RoomMode.STANDARD, false, Language.EN).contentTone())
                .as("friends by default")
                .isEqualTo(Tone.CHEEKY);
        assertThat(Company.FAMILY.allows(Tone.SPICY)).isFalse();
        assertThat(Company.COUPLES.allows(Tone.SPICY)).isTrue();
        assertThatThrownBy(() -> GameRuleUtils.requireFits(Company.COLLEAGUES, Tone.SPICY))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void theOwnersLineIsCleanedAndChecked() {
        assertThat(GameRuleUtils.groupContext("  classmates,   ten years on ")).isEqualTo("classmates, ten years on");
        assertThat(GameRuleUtils.groupContext("   ")).isNull();
        assertThat(GameRuleUtils.groupContext(null)).isNull();
        assertThatThrownBy(() -> GameRuleUtils.groupContext("x".repeat(GameRuleUtils.MAX_CONTEXT_CHARS + 1)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> GameRuleUtils.groupContext("call me at ann@example.com"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void theOwnerChangesWhoCameInTheLobby() {
        GameHarness.Party party = h.party(3);
        h.owner(party, "game.settings", Map.of("company", "COLLEAGUES", "context", "Friday demo crew"));

        RoomSettings now = h.read(party.room(), RoomState::getSettings);
        assertThat(now.company()).isEqualTo(Company.COLLEAGUES);
        assertThat(now.context()).isEqualTo("Friday demo crew");
        assertThat(h.send(
                                party.room(),
                                party.owner(),
                                "game.settings",
                                Map.of("tone", "SPICY", "adultsConfirmed", true))
                        .error())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        h.owner(party, "game.settings", Map.of("company", "FRIENDS"));
        assertThat(h.read(party.room(), RoomState::getSettings).context())
                .as("the line stays until the owner changes it")
                .isEqualTo("Friday demo crew");
    }

    @Test
    void onlyTheOwnersScreenShowsTheLineAboutTheGroup() {
        GameHarness.Party party = h.party(3, COLLEAGUES);

        RoomSettingsDto owner = h.view(party.room(), party.owner()).settings();
        assertThat(owner.company()).isEqualTo(Company.COLLEAGUES);
        assertThat(owner.context()).isEqualTo("Sales team");
        RoomSettingsDto player = h.view(party, party.seat(0)).settings();
        assertThat(player.company()).isEqualTo(Company.COLLEAGUES);
        assertThat(player.context()).isNull();
        assertThat(h.view(party.room(), h.viewer(party.room())).settings().context())
                .isNull();
    }
}
