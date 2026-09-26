package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insidejoke.analytics.AnalyticsService;
import com.insidejoke.common.ApiException;
import com.insidejoke.common.AppProperties;
import com.insidejoke.common.ErrorCode;
import com.insidejoke.common.Language;
import com.insidejoke.game.dto.RoomStateDto;
import com.insidejoke.moderation.ModerationEventRepository;
import com.insidejoke.settings.AppSettingsService;
import com.insidejoke.support.ManualClock;
import com.insidejoke.support.ManualExecutor;
import com.insidejoke.support.ManualScheduler;
import com.insidejoke.support.PropertyDefaults;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The game engine with everything around it replaced (blueprint 14): a fake AI host, a clock and timers that move only
 * when the test moves them, background work that runs on the test's thread, and a seeded random generator. The real
 * engine and phase handlers run; no Spring, database or network is involved, so a whole game takes milliseconds and
 * plays out the same way every time.
 */
final class GameHarness {

    static final UUID HOST = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final List<String> NAMES = List.of("Ann", "Ben", "Cat", "Dan", "Eve", "Fay", "Gus", "Hal", "Ivy");
    static final RoomSettings SETTINGS =
            new RoomSettings(Tone.CHEEKY, GameLength.SHORT, RoomMode.STANDARD, false, Language.EN);

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final FallbackContentService FALLBACK = new FallbackContentService(JSON);

    final ManualClock clock = new ManualClock();
    final ManualScheduler timers = new ManualScheduler(clock);
    final ManualExecutor background = new ManualExecutor();
    final FakeHostAi ai = new FakeHostAi();
    final FakeAccess access = new FakeAccess();
    final RecordingEvents events = new RecordingEvents();
    final AnalyticsService analytics = mock(AnalyticsService.class);
    final GameSessionRepository sessions = mock(GameSessionRepository.class);
    final ModerationEventRepository moderation = mock(ModerationEventRepository.class);
    final AppSettingsService settings = mock(AppSettingsService.class);
    final RoomRegistryService registry = new RoomRegistryService();
    final GameProperties props = PropertyDefaults.of("app.game", GameProperties.class);
    final GameEngineService engine;
    final RoomProjectionService projection;

    /** What {@link AppSettingsService#get()} returns; tests replace it to switch drain mode or the viewer cap. */
    AppSettingsService.Snapshot snapshot = new AppSettingsService.Snapshot(true, true, 5_000_000, 2000, false);

    private boolean held;

    GameHarness() {
        when(settings.get()).thenAnswer(call -> snapshot);
        AppProperties app = new AppProperties("http://localhost", List.of(), List.of(), null);
        engine = new GameEngineService(
                registry,
                props,
                settings,
                clock,
                timers,
                background,
                ai,
                FALLBACK,
                access,
                sessions,
                moderation,
                analytics,
                events,
                new Random(7));
        engine.wire();
        projection = new RoomProjectionService(app, FALLBACK, props, ai, clock);
    }

    // ---------------------------------------------------------------- a party

    /** A player's seat: their id, name and the member their phone speaks as. */
    record Seat(String id, String name, String token, Member member) {}

    /** A room with its owner's screen and the players who joined, in joining order. */
    record Party(RoomState room, Member owner, List<Seat> seats) {

        Seat seat(int i) {
            return seats.get(i);
        }

        Seat captain() {
            String id = room.call(RoomState::getCaptainId);
            return seats.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
        }

        Seat byId(String id) {
            return seats.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
        }
    }

    Party party(int players) {
        return party(players, SETTINGS);
    }

    /** A new room whose owner's screen is connected, with {@code players} players joined and connected. */
    Party party(int players, RoomSettings roomSettings) {
        RoomState room = engine.createRoom(HOST, roomSettings);
        Member owner = member(room, room.getOwnerToken());
        engine.connected(room, owner);
        settle();
        Party party = new Party(room, owner, new ArrayList<>());
        for (int i = 0; i < players; i++) {
            join(party, NAMES.get(i));
        }
        return party;
    }

    /** A room created by an admin, so its owner may add test bots (roadmap R34); nobody has joined yet. */
    Party adminParty() {
        RoomState room = engine.createRoom(HOST, SETTINGS, true);
        Member owner = member(room, room.getOwnerToken());
        engine.connected(room, owner);
        settle();
        return new Party(room, owner, new ArrayList<>());
    }

    Seat join(Party party, String name) {
        GameEngineService.JoinedPlayer joined = engine.joinPlayer(party.room(), name, null);
        Member member = member(party.room(), joined.token());
        engine.connected(party.room(), member);
        settle();
        Seat seat = new Seat(joined.playerId(), name, joined.token(), member);
        party.seats().add(seat);
        return seat;
    }

    /** The error a join attempt fails with. */
    ErrorCode joinError(RoomState room, String name) {
        try {
            engine.joinPlayer(room, name, null);
        } catch (ApiException e) {
            return e.code();
        }
        throw new AssertionError("Joining as " + name + " should have failed");
    }

    Member screenCopy(RoomState room) {
        Member copy = member(room, engine.joinScreen(room));
        engine.connected(room, copy);
        settle();
        return copy;
    }

    Member viewer(RoomState room) {
        Member viewer = member(room, engine.joinAudience(room));
        engine.connected(room, viewer);
        settle();
        return viewer;
    }

    private static Member member(RoomState room, String token) {
        return room.call(r -> r.getMembers().get(token));
    }

    void disconnect(RoomState room, Member member) {
        engine.disconnected(room, member);
        settle();
    }

    void reconnect(RoomState room, Member member) {
        engine.connected(room, member);
        settle();
    }

    // ---------------------------------------------------------------- commands

    /** A client's request and the answer it got (possibly after background work, such as a moderation check). */
    static final class Answer {
        private Map<String, Object> ok;
        private ApiException error;
        private final String what;

        private Answer(String what) {
            this.what = what;
        }

        /** Where the engine sends its answer. */
        private ReplyHandler handler() {
            return new ReplyHandler() {
                @Override
                public void ok(Map<String, Object> data) {
                    ok = data;
                }

                @Override
                public void error(ApiException e) {
                    error = e;
                }
            };
        }

        /** The data of a successful answer; fails the test otherwise. */
        Map<String, Object> ok() {
            assertThat(error).as(what + " failed").isNull();
            assertThat(ok).as(what + " was answered").isNotNull();
            return ok;
        }

        /** The code of a refusal; fails the test otherwise. */
        ErrorCode error() {
            assertThat(error).as(what + " should have been refused, got " + ok).isNotNull();
            return error.code();
        }

        ApiException exception() {
            error();
            return error;
        }
    }

    Answer send(RoomState room, Member from, String type, Map<String, ?> data) {
        Answer answer = new Answer(type);
        JsonNode body = JSON.valueToTree(data);
        engine.command(room, from, null, type, body, answer.handler());
        settle();
        return answer;
    }

    Answer send(RoomState room, Member from, String type) {
        return send(room, from, type, Map.of());
    }

    Answer send(Party party, Seat from, String type, Map<String, ?> data) {
        return send(party.room(), from.member(), type, data);
    }

    Answer send(Party party, Seat from, String type) {
        return send(party.room(), from.member(), type, Map.of());
    }

    /** The owner's screen sends {@code type}; it must succeed. */
    void owner(Party party, String type, Map<String, ?> data) {
        send(party.room(), party.owner(), type, data).ok();
    }

    void owner(Party party, String type) {
        owner(party, type, Map.of());
    }

    // ---------------------------------------------------------------- time and background work

    /** Runs the background work queued so far, unless the test is holding it back. */
    void settle() {
        if (!held) {
            background.runAll();
        }
    }

    /** From now on, background work (AI calls, database writes) waits: the AI is "late". */
    void hold() {
        held = true;
    }

    /** The late work arrives. */
    void release() {
        held = false;
        settle();
    }

    /** Moves time forward; every timer due on the way fires at its moment, followed by the work it started. */
    void advance(Duration by) {
        long target = clock.millis() + by.toMillis();
        OptionalLong next;
        while ((next = timers.nextAt()).isPresent() && next.getAsLong() <= target) {
            clock.setMillis(next.getAsLong());
            timers.runDue();
            settle();
        }
        clock.setMillis(target);
        settle();
    }

    /** The housekeeping pass the scheduler runs every 30 s: closes idle and expired rooms, forgets closed ones. */
    void sweep() {
        engine.sweep();
        settle();
    }

    /** Moves time just past the room's next timer (a phase deadline, the host thinking, the wait for players). */
    void expire(RoomState room) {
        long wake = room.call(engine::nextWake);
        assertThat(wake).as("the room has a timer running").isNotEqualTo(Long.MAX_VALUE);
        advance(Duration.ofMillis(Math.max(0, wake - clock.millis()) + 10));
    }

    // ---------------------------------------------------------------- reading the room

    <T> T read(RoomState room, Function<RoomState, T> read) {
        return room.call(read);
    }

    Phase phase(Party party) {
        return read(party.room(), RoomState::getPhase);
    }

    RoundState round(Party party) {
        return read(party.room(), RoomState::getRound);
    }

    PlayerState player(Party party, Seat seat) {
        return read(party.room(), (RoomState r) -> r.getPlayers().get(seat.id()));
    }

    int score(Party party, Seat seat) {
        return read(party.room(), (RoomState r) -> r.getPlayers().get(seat.id()).getScore());
    }

    RoomStateDto view(RoomState room, Member member) {
        return room.call(r -> projection.project(r, member));
    }

    RoomStateDto view(Party party, Seat seat) {
        return view(party.room(), seat.member());
    }

    /** Every member's view serialized, to check that something reaches nobody. */
    String allViews(RoomState room) {
        List<Member> members = read(room, r -> List.copyOf(r.getMembers().values()));
        StringBuilder all = new StringBuilder();
        for (Member m : members) {
            all.append(JSON.writeValueAsString(view(room, m))).append('\n');
        }
        return all.toString();
    }

    // ---------------------------------------------------------------- moving a game along

    /** The captain starts the game; players who never answered the questions get the intake. */
    void start(Party party) {
        send(party, party.captain(), "game.start").ok();
    }

    /** Every player answers the three questions; the last answer ends the intake. */
    void fillIntake(Party party) {
        for (Seat seat : party.seats()) {
            if (read(
                    party.room(),
                    (RoomState r) -> !r.getPlayers().get(seat.id()).isRemoved())) {
                send(party, seat, "intake.submit", Map.of("answers", List.of("Pizza", "Karaoke", "Lost a shoe")))
                        .ok();
            }
        }
    }

    /** Starts the game and fills the intake: round 1, an answer duel, is being written. */
    void toAnswering(Party party) {
        start(party);
        fillIntake(party);
        assertThat(phase(party)).isEqualTo(Phase.ANSWERING);
    }

    /** The duels {@code seat} writes for in the current round. */
    List<DuelState> duelsOf(Party party, Seat seat) {
        return read(
                party.room(),
                (RoomState r) -> r.getRound().getDuels().stream()
                        .filter(d -> d.involves(seat.id()))
                        .toList());
    }

    /** Every present player answers every prompt: "{name}'s answer to {duel}". */
    void answerAll(Party party) {
        for (Seat seat : party.seats()) {
            for (DuelState d : duelsOf(party, seat)) {
                if (phase(party) == Phase.ANSWERING && d.answerOf(seat.id()) == null) {
                    send(party, seat, "answer.submit", Map.of("duelId", d.getId(), "text", answerText(seat, d)))
                            .ok();
                }
            }
        }
    }

    static String answerText(Seat seat, DuelState d) {
        return seat.name() + "'s answer to " + d.getId();
    }

    /** The players who may vote on the current step. */
    List<Seat> voters(Party party) {
        return read(
                party.room(),
                (RoomState r) -> party.seats().stream()
                        .filter(s -> r.getRound().currentVote().getEligible().contains(s.id()))
                        .toList());
    }

    /** The owner presses "next" until the room reaches {@code phase}. */
    void skipTo(Party party, Phase phase) {
        for (int i = 0; i < 100 && phase(party) != phase; i++) {
            Phase now = phase(party);
            if (now == Phase.ANSWERING) {
                answerAll(party);
            } else {
                owner(party, "game.next");
            }
        }
        assertThat(phase(party)).isEqualTo(phase);
    }

    /** Plays the current game to the finale: round kinds voted {@code kind}, duels answered, every vote skipped. */
    void playToFinale(Party party, RoundKind kind) {
        for (int i = 0; i < 300 && phase(party) != Phase.FINALE; i++) {
            switch (phase(party)) {
                case INTAKE -> fillIntake(party);
                case ROUND_VOTE -> {
                    party.seats().forEach(s -> send(party, s, "round.kind.vote", Map.of("kind", kind.name())));
                    owner(party, "game.next");
                }
                case ANSWERING -> answerAll(party);
                default -> owner(party, "game.next");
            }
        }
        assertThat(phase(party)).isEqualTo(Phase.FINALE);
    }

    // ---------------------------------------------------------------- the engine's other collaborators

    /** Billing's side of starting a game: allows every start unless told to refuse. */
    static final class FakeAccess implements GameAccessPort {
        ErrorCode refusal;
        RuntimeException failure;
        final List<StartParams> starts = new ArrayList<>();

        @Override
        public Started start(StartParams params) {
            starts.add(params);
            if (failure != null) {
                throw failure;
            }
            if (refusal != null) {
                throw new ApiException(refusal);
            }
            return new Started(UUID.randomUUID(), true, null);
        }
    }

    /** What the engine told the WebSocket layer. */
    static final class RecordingEvents implements RoomEventListener {
        int changes;
        final List<String> kicked = new ArrayList<>();
        final List<String> closed = new ArrayList<>();

        @Override
        public void changed(RoomState room) {
            changes++;
        }

        @Override
        public void kicked(RoomState room, String playerId) {
            kicked.add(playerId);
        }

        @Override
        public void closed(RoomState room) {
            closed.add(room.getCode());
        }
    }
}
