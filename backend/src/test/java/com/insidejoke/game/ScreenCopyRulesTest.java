package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Remote screen copies (user flow R1, audit 19): a room holds a limited number; a copy nobody has watched for a while
 * gives its token to a new one, a copy in use or just dropped keeps it.
 */
class ScreenCopyRulesTest {

    private final GameHarness h = new GameHarness();

    private List<Member> fill(RoomState room) {
        List<Member> copies = new ArrayList<>();
        for (int i = 0; i < h.props.maxScreenCopies(); i++) {
            copies.add(h.screenCopy(room));
        }
        return copies;
    }

    private int copies(RoomState room) {
        return h.read(room, (RoomState r) -> r.getScreenCopies().size());
    }

    private static void assertFull(RoomState room, GameEngineService engine) {
        assertThatThrownBy(() -> engine.joinScreen(room))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SCREENS_FULL));
    }

    @Test
    void aRoomHoldsCopiesOnlyUpToTheLimit() {
        GameHarness.Party party = h.party(3);
        List<Member> copies = fill(party.room());

        assertFull(party.room(), h.engine);
        h.advance(h.props.disconnectGrace().plusMinutes(10));
        assertFull(party.room(), h.engine);
        assertThat(copies)
                .allSatisfy(c -> assertThat(h.engine.resolve(c.token())).isPresent());
        assertThat(copies(party.room())).isEqualTo(h.props.maxScreenCopies());
    }

    @Test
    void aCopyNobodyWatchesGivesItsTokenToANewOneAfterTheGrace() {
        GameHarness.Party party = h.party(3);
        List<Member> copies = fill(party.room());
        Member closed = copies.get(2);
        h.disconnect(party.room(), closed);

        assertFull(party.room(), h.engine);
        h.advance(h.props.disconnectGrace());
        String fresh = h.engine.joinScreen(party.room());

        assertThat(h.engine.resolve(closed.token()))
                .as("the idle copy's token is taken back")
                .isEmpty();
        assertThat(h.engine.resolve(fresh)).isPresent();
        assertThat(h.read(party.room(), (RoomState r) -> r.getMembers().containsKey(closed.token())))
                .isFalse();
        assertThat(copies(party.room())).isEqualTo(h.props.maxScreenCopies());
        assertFull(party.room(), h.engine);
    }

    @Test
    void theLongestIdleCopyGoesFirst() {
        GameHarness.Party party = h.party(3);
        List<Member> copies = fill(party.room());
        h.disconnect(party.room(), copies.get(5));
        h.advance(Duration.ofSeconds(5));
        h.disconnect(party.room(), copies.get(1));
        h.advance(h.props.disconnectGrace());

        h.engine.joinScreen(party.room());

        assertThat(h.engine.resolve(copies.get(5).token())).isEmpty();
        assertThat(h.engine.resolve(copies.get(1).token())).isPresent();
    }

    @Test
    void aCopyThatNeverConnectedCountsAsIdleFromWhenItWasIssued() {
        GameHarness.Party party = h.party(3);
        String unused = h.engine.joinScreen(party.room());
        for (int i = 1; i < h.props.maxScreenCopies(); i++) {
            h.screenCopy(party.room());
        }
        assertFull(party.room(), h.engine);

        h.advance(h.props.disconnectGrace());
        h.engine.joinScreen(party.room());

        assertThat(h.engine.resolve(unused)).isEmpty();
    }

    @Test
    void aCopyOpenInTwoTabsStaysInUseUntilBothClose() {
        GameHarness.Party party = h.party(3);
        List<Member> copies = fill(party.room());
        Member shared = copies.getFirst();
        h.reconnect(party.room(), shared);
        h.disconnect(party.room(), shared);
        h.advance(h.props.disconnectGrace());
        assertFull(party.room(), h.engine);

        h.disconnect(party.room(), shared);
        h.advance(h.props.disconnectGrace());
        h.engine.joinScreen(party.room());
        assertThat(h.engine.resolve(shared.token())).isEmpty();
    }

    @Test
    void aSocketOfATokenAlreadyTakenBackChangesNothing() {
        GameHarness.Party party = h.party(3);
        List<Member> copies = fill(party.room());
        Member gone = copies.getLast();
        h.disconnect(party.room(), gone);
        h.advance(h.props.disconnectGrace());
        h.engine.joinScreen(party.room());

        h.reconnect(party.room(), gone);
        h.disconnect(party.room(), gone);

        assertThat(h.read(party.room(), (RoomState r) -> r.getScreenCopies().containsKey(gone.token())))
                .isFalse();
        assertThat(copies(party.room())).isEqualTo(h.props.maxScreenCopies());
    }

    @Test
    void everyCopySeesTheSameScreen() {
        GameHarness.Party party = h.party(3);
        Member first = h.screenCopy(party.room());
        Member second = h.screenCopy(party.room());

        assertThat(h.view(party.room(), first)).usingRecursiveComparison().isEqualTo(h.view(party.room(), second));
        assertThat(h.view(party.room(), first).you().role()).isEqualTo(Role.SCREEN);
    }
}
