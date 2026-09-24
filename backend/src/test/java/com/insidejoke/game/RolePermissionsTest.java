package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.common.ErrorCode;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The permission matrix of blueprint 5.3 and the user flow's owner menu, command by command. */
class RolePermissionsTest {

    private static final Set<Role> OWNER = EnumSet.of(Role.OWNER_SCREEN);
    private static final Set<Role> CONTROL = EnumSet.of(Role.OWNER_SCREEN, Role.CAPTAIN);
    private static final Set<Role> PLAYERS = EnumSet.of(Role.CAPTAIN, Role.PLAYER);

    private static final Map<CommandType, Set<Role>> MATRIX = Map.ofEntries(
            Map.entry(CommandType.GAME_START, CONTROL),
            Map.entry(CommandType.GAME_NEXT, CONTROL),
            Map.entry(CommandType.GAME_AGAIN, CONTROL),
            Map.entry(CommandType.GAME_END, OWNER),
            Map.entry(CommandType.GAME_PAUSE, OWNER),
            Map.entry(CommandType.GAME_RESUME, OWNER),
            Map.entry(CommandType.GAME_SETTINGS, OWNER),
            Map.entry(CommandType.PAYWALL_DISMISS, OWNER),
            Map.entry(CommandType.PLAYER_KICK, OWNER),
            Map.entry(CommandType.CAPTAIN_SET, OWNER),
            Map.entry(CommandType.ROOM_LOCK, OWNER),
            Map.entry(CommandType.ROOM_CLOSE, OWNER),
            Map.entry(CommandType.INTAKE_SUBMIT, PLAYERS),
            Map.entry(CommandType.DOSSIER_ADD, PLAYERS),
            Map.entry(CommandType.ROUND_KIND_VOTE, PLAYERS),
            Map.entry(CommandType.ANSWER_SUBMIT, PLAYERS),
            Map.entry(CommandType.VOTE_SUBMIT, PLAYERS),
            Map.entry(CommandType.FEEDBACK_QUICK, PLAYERS),
            Map.entry(CommandType.AUDIENCE_VOTE, EnumSet.of(Role.AUDIENCE)));

    @ParameterizedTest
    @EnumSource(value = CommandType.class, names = "LINE_SKIP", mode = EnumSource.Mode.EXCLUDE)
    void eachCommandIsAllowedExactlyToItsRoles(CommandType command) {
        Set<Role> allowed = EnumSet.allOf(Role.class).stream()
                .filter(role -> command.action().orElseThrow().allowedFor(role))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Role.class)));
        assertThat(allowed).isEqualTo(MATRIX.get(command));
    }

    @Test
    void skippingALineIsAnyoneLinesForTheOwnerAndOwnLinesForPlayers() {
        assertThat(CommandType.LINE_SKIP.action()).as("the handler decides").isEmpty();
        assertThat(Action.SKIP_ANY_LINE.allowedFor(Role.OWNER_SCREEN)).isTrue();
        assertThat(EnumSet.allOf(Role.class).stream().filter(Action.SKIP_OWN_LINE::allowedFor))
                .containsExactlyInAnyOrder(Role.OWNER_SCREEN, Role.CAPTAIN, Role.PLAYER);
    }

    @Test
    void theEngineRefusesWhatARoleMayNotDo() {
        GameHarness h = new GameHarness();
        GameHarness.Party party = h.party(3);
        GameHarness.Seat captain = party.captain();
        GameHarness.Seat player = party.seat(1);
        Member copy = h.screenCopy(party.room());

        assertThat(h.send(party, player, "game.start").error()).isEqualTo(ErrorCode.NOT_ALLOWED);
        assertThat(h.send(party, player, "player.kick", Map.of("playerId", captain.id()))
                        .error())
                .isEqualTo(ErrorCode.NOT_ALLOWED);
        assertThat(h.send(party, captain, "game.settings", Map.of("length", "LONG"))
                        .error())
                .isEqualTo(ErrorCode.NOT_ALLOWED);
        assertThat(h.send(party, captain, "room.close").error()).isEqualTo(ErrorCode.NOT_ALLOWED);
        assertThat(h.send(party.room(), party.owner(), "dossier.add", Map.of("text", "anything"))
                        .error())
                .isEqualTo(ErrorCode.NOT_ALLOWED);
        assertThat(h.send(party.room(), party.owner(), "audience.vote", Map.of("optionId", "A"))
                        .error())
                .isEqualTo(ErrorCode.NOT_ALLOWED);
        assertThat(h.send(party.room(), copy, "game.start").error()).isEqualTo(ErrorCode.NOT_ALLOWED);
        assertThat(h.send(party.room(), copy, "game.pause").error()).isEqualTo(ErrorCode.NOT_ALLOWED);
        assertThat(h.send(party.room(), copy, "dance").error()).isEqualTo(ErrorCode.BAD_MESSAGE);
        assertThat(h.phase(party)).isEqualTo(Phase.LOBBY);
    }
}
