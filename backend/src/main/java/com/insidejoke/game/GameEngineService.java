package com.insidejoke.game;

import com.insidejoke.analytics.AnalyticsService;
import com.insidejoke.common.ApiException;
import com.insidejoke.common.AppProperties;
import com.insidejoke.common.ErrorCode;
import com.insidejoke.common.TokenUtils;
import com.insidejoke.game.dto.LiveStatsDto;
import com.insidejoke.game.dto.RoomStatusDto;
import com.insidejoke.moderation.ContentRuleUtils;
import com.insidejoke.moderation.ModerationAction;
import com.insidejoke.moderation.ModerationEventRepository;
import com.insidejoke.moderation.ModerationStage;
import com.insidejoke.settings.AppSettingsService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;

/**
 * The game rules. Every change to a room runs through {@link #mutate} under that room's lock, strictly one at a
 * time (blueprint 4.2). Anything slow (AI, database, speech) runs in a virtual thread via {@link #async} and comes
 * back as a new command, so no lock is ever held across a network call. Chance (duel pairs, the host's picks) comes
 * from the injected {@link RandomGenerator}, so a test with a seeded one replays a game exactly.
 */
@Service
public class GameEngineService implements GameRuntimeService {

    public static final List<String> EMOJIS = List.of(
            "🦊", "🐙", "🦄", "🐸", "🐼", "🦉", "🐯", "🐨", "🦖", "🐝", "🦩", "🐳", "🦔", "🐲", "🦜", "🐢", "🦀", "🐧",
            "🦦", "🐞", "🍄", "🌵", "🍩", "🎸");
    public static final String PROMPT_VERSION = "round_gen.v2";

    public record JoinedPlayer(String playerId, String token) {}

    public record Resolved(RoomState room, Member member) {}

    private static final Logger log = LoggerFactory.getLogger(GameEngineService.class);

    private final RoomRegistryService registry;
    private final GameProperties props;
    private final AppSettingsService settings;
    private final AppProperties app;
    private final Clock clock;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService io;
    private final HostAiService ai;
    private final FallbackContentService fallback;
    private final GameAccessPort access;
    private final GameSessionRepository sessions;
    private final ModerationEventRepository moderationEvents;
    private final AnalyticsService analytics;
    private final RoomEventListener events;
    private final RandomGenerator random;
    private LobbyPhaseHandler lobby;
    private IntakePhaseHandler intake;
    private RoundPhaseHandler rounds;
    private VotingPhaseHandler voting;
    private FinalePhaseHandler finale;
    private Map<CommandType, CommandHandler> commands;
    private Map<Phase, PhaseHandler> phases;

    public GameEngineService(
            RoomRegistryService registry,
            GameProperties props,
            AppSettingsService settings,
            AppProperties app,
            Clock clock,
            ScheduledExecutorService gameScheduler,
            ExecutorService ioExecutor,
            HostAiService ai,
            FallbackContentService fallback,
            GameAccessPort access,
            GameSessionRepository sessions,
            ModerationEventRepository moderationEvents,
            AnalyticsService analytics,
            RoomEventListener events,
            RandomGenerator gameRandom) {
        this.registry = registry;
        this.props = props;
        this.settings = settings;
        this.app = app;
        this.clock = clock;
        this.scheduler = gameScheduler;
        this.io = ioExecutor;
        this.ai = ai;
        this.fallback = fallback;
        this.access = access;
        this.sessions = sessions;
        this.moderationEvents = moderationEvents;
        this.analytics = analytics;
        this.events = events;
        this.random = gameRandom;
    }

    /**
     * Builds the phase handlers once this object is fully constructed: each gets the coordinator only through
     * {@link GameRuntimeService} and its own collaborators explicitly. The handler fields are assigned here and never again.
     */
    @PostConstruct
    void wire() {
        lobby = new LobbyPhaseHandler(this, props, clock, fallback, access, analytics, events, random);
        intake = new IntakePhaseHandler(this, props, ai, analytics);
        rounds = new RoundPhaseHandler(this, props, ai, fallback, analytics, random);
        voting = new VotingPhaseHandler(this, props, ai);
        finale = new FinalePhaseHandler(this, ai, fallback, random);
        commands = commandTable();
        phases = new EnumMap<>(Phase.class);
        phases.put(Phase.INTAKE, intake::endIntake);
        phases.put(Phase.ROUND_VOTE, rounds::endRoundVote);
        phases.put(Phase.ANSWERING, voting::endAnswering);
        phases.put(Phase.VOTING, voting::reveal);
        phases.put(Phase.REVEAL, voting::advance);
    }

    // ------------------------------------------------------------------ rooms and members (REST side)

    public RoomState createRoom(UUID ownerUserId, RoomSettings roomSettings) {
        if (settings.get().drainMode()) {
            throw new ApiException(ErrorCode.DRAIN_MODE);
        }
        for (RoomState old : registry.ownedBy(ownerUserId)) {
            boolean abandoned = old.call(
                    r -> r.getPhase() == Phase.LOBBY && r.activePlayers().isEmpty() && r.getGameNumber() == 0);
            if (abandoned) {
                close(old, EndReason.ENDED_BY_HOST);
            }
        }
        Instant now = clock.instant();
        RoomState room = registry.register((code, audienceKey) -> {
            RoomState r = new RoomState(code, audienceKey, ownerUserId, TokenUtils.random(18), roomSettings, now);
            r.putMember(r.getOwnerToken(), new Member(r.getOwnerToken(), MemberKind.OWNER_SCREEN, null, null));
            r.setIntakeQuestions(fallback.intakeQuestions(roomSettings.language(), roomSettings.tone(), random));
            return r;
        });
        mutate(room, r -> say(r, line(r, "lobbyWaiting", Map.of()), Set.of()));
        analytics.track(
                "room_created",
                ownerUserId.toString(),
                Map.of(
                        "tone",
                        roomSettings.tone().name(),
                        "length",
                        roomSettings.length().name(),
                        "mode",
                        roomSettings.mode().name(),
                        "language",
                        roomSettings.language().code()));
        return room;
    }

    public Optional<RoomState> find(String code) {
        return registry.find(code).filter(room -> room.call(r -> r.getPhase() != Phase.CLOSED));
    }

    /** The open room behind a viewers' link. */
    public Optional<RoomState> findByAudienceKey(String key) {
        return registry.byAudienceKey(key).filter(r -> r.call(x -> x.getPhase() != Phase.CLOSED));
    }

    public RoomStatusDto status(RoomState room) {
        return room.call(r -> {
            int players = r.activePlayers().size();
            boolean full = players >= props.maxPlayers();
            boolean openPhase = r.getPhase() == Phase.LOBBY || r.getPhase() == Phase.INTAKE;
            return new RoomStatusDto(
                    r.getCode(),
                    r.getPhase(),
                    r.getSettings().mode(),
                    players,
                    props.maxPlayers(),
                    r.isLocked(),
                    full,
                    openPhase && !r.isLocked() && !full,
                    r.getSettings().mode() == RoomMode.STREAMER && r.getPhase() != Phase.CLOSED,
                    r.getSettings().hideCode(),
                    r.getSettings().mode() == RoomMode.STREAMER ? r.getAudienceKey() : null,
                    r.getSettings().language().code());
        });
    }

    public Optional<Resolved> resolve(String token) {
        return registry.byToken(token)
                .flatMap(room -> room.call(r -> {
                    Member m = r.getMembers().get(token);
                    if (m == null || r.getPhase() == Phase.CLOSED) {
                        return Optional.<Resolved>empty();
                    }
                    if (m.kind() == MemberKind.PLAYER
                            && r.getPlayers().get(m.playerId()).isRemoved()) {
                        return Optional.<Resolved>empty();
                    }
                    return Optional.of(new Resolved(room, m));
                }));
    }

    public JoinedPlayer joinPlayer(RoomState room, String rawName, String rawEmoji) {
        String name = ContentRuleUtils.clean(rawName);
        JoinedPlayer[] result = new JoinedPlayer[1];
        mutate(room, r -> {
            if (r.getPhase() == Phase.CLOSED) {
                throw new ApiException(ErrorCode.ROOM_NOT_FOUND);
            }
            if (r.getPhase() != Phase.LOBBY && r.getPhase() != Phase.INTAKE) {
                throw new ApiException(ErrorCode.ROOM_IN_PROGRESS);
            }
            if (r.isLocked()) {
                throw new ApiException(ErrorCode.ROOM_LOCKED);
            }
            if (r.activePlayers().size() >= props.maxPlayers()) {
                throw new ApiException(ErrorCode.ROOM_FULL);
            }
            int length = name.codePointCount(0, name.length());
            if (length < 1 || length > props.maxNameChars() || !ContentRuleUtils.acceptableName(name)) {
                throw new ApiException(ErrorCode.NAME_INVALID);
            }
            if (r.activePlayers().stream().anyMatch(p -> p.getName().equalsIgnoreCase(name))) {
                throw new ApiException(ErrorCode.NAME_TAKEN);
            }
            String emoji = rawEmoji != null && EMOJIS.contains(rawEmoji)
                    ? rawEmoji
                    : EMOJIS.get(random.nextInt(EMOJIS.size()));
            String token = TokenUtils.random(18);
            PlayerState p = new PlayerState(r.nextId("p"), token, name, emoji, clock.instant());
            p.setDisconnectedAt(clock.instant());
            r.putPlayer(p.getId(), p);
            r.putMember(token, new Member(token, MemberKind.PLAYER, p.getId(), null));
            registry.indexToken(token, r);
            if (r.getCaptainId() == null || r.getPlayers().get(r.getCaptainId()).isRemoved()) {
                r.setCaptainId(p.getId());
            }
            if (r.getPhase() == Phase.LOBBY) {
                say(r, line(r, "lobbyGreeting", Map.of("name", name)), Set.of(p.getId()));
            }
            result[0] = new JoinedPlayer(p.getId(), token);
        });
        analytics.track("player_joined", room.getCode() + ":" + result[0].playerId(), Map.of("room", room.getCode()));
        return result[0];
    }

    public String joinScreen(RoomState room) {
        String token = TokenUtils.random(18);
        mutate(room, r -> {
            if (r.getPhase() == Phase.CLOSED) {
                throw new ApiException(ErrorCode.ROOM_NOT_FOUND);
            }
            r.putMember(token, new Member(token, MemberKind.SCREEN, null, null));
            registry.indexToken(token, r);
        });
        return token;
    }

    public String joinAudience(RoomState room) {
        String token = TokenUtils.random(18);
        mutate(room, r -> {
            if (r.getPhase() == Phase.CLOSED) {
                throw new ApiException(ErrorCode.ROOM_NOT_FOUND);
            }
            if (r.getSettings().mode() != RoomMode.STREAMER) {
                throw new ApiException(ErrorCode.NOT_STREAMER_MODE);
            }
            if (r.getAudienceCount() >= settings.get().audienceCap()) {
                throw new ApiException(ErrorCode.AUDIENCE_FULL);
            }
            r.putMember(token, new Member(token, MemberKind.AUDIENCE, null, r.nextId("a")));
            registry.indexToken(token, r);
        });
        analytics.track(
                "audience_joined", room.getCode() + ":" + token.substring(0, 8), Map.of("room", room.getCode()));
        return token;
    }

    public void connected(RoomState room, Member m) {
        mutate(room, r -> {
            switch (m.kind()) {
                case PLAYER -> {
                    PlayerState p = r.getPlayers().get(m.playerId());
                    p.addConnections(1);
                    p.setDisconnectedAt(null);
                }
                case OWNER_SCREEN -> {
                    r.addOwnerScreens(1);
                    if (r.getPause() == PauseReason.SCREEN_LOST) {
                        r.setWelcomeBack(true);
                    }
                    r.setOwnerScreenLostAt(null);
                }
                case AUDIENCE -> {
                    r.addAudienceCount(1);
                    r.setAudiencePeak(Math.max(r.getAudiencePeak(), r.getAudienceCount()));
                }
                case SCREEN -> {
                    return;
                }
            }
            checkPlayerCount(r);
        });
    }

    public void disconnected(RoomState room, Member m) {
        mutate(room, r -> {
            switch (m.kind()) {
                case PLAYER -> {
                    PlayerState p = r.getPlayers().get(m.playerId());
                    p.setConnections(Math.max(0, p.getConnections() - 1));
                    if (p.getConnections() == 0) {
                        p.setDisconnectedAt(clock.instant());
                    }
                }
                case OWNER_SCREEN -> {
                    r.setOwnerScreens(Math.max(0, r.getOwnerScreens() - 1));
                    if (r.getOwnerScreens() == 0) {
                        r.setOwnerScreenLostAt(clock.instant());
                    }
                }
                case AUDIENCE -> r.setAudienceCount(Math.max(0, r.getAudienceCount() - 1));
                case SCREEN -> {
                    return;
                }
            }
        });
    }

    // ------------------------------------------------------------------ commands (WebSocket side)

    /**
     * Handles one client message. Errors go to {@code reply}; success may be replied later (after moderation). A copy of
     * a request this member already sent with the same {@code reqId} (re-sent after a reconnect) is not run again: it
     * gets the first answer, now or when it comes.
     */
    public void command(RoomState room, Member m, String reqId, String type, JsonNode data, ReplyHandler reply) {
        JsonNode body = data == null ? NullNode.getInstance() : data;
        if (reqId == null) {
            run(room, m, type, body, reply);
            return;
        }
        room.getRequests().begin(m.token(), reqId, type, body, reply).ifPresent(tracked -> {
            try {
                run(room, m, type, body, tracked);
            } catch (RuntimeException e) {
                tracked.abandon();
                throw e;
            }
        });
    }

    private void run(RoomState room, Member m, String type, JsonNode data, ReplyHandler reply) {
        try {
            mutate(room, r -> dispatch(r, m, type, data, reply));
        } catch (ApiException e) {
            reply.error(e);
        }
    }

    void dispatch(RoomState r, Member m, String type, JsonNode data, ReplyHandler reply) {
        if (r.getPhase() == Phase.CLOSED) {
            throw new ApiException(ErrorCode.ROOM_NOT_FOUND);
        }
        Role role = r.roleOf(m);
        if (m.kind() == MemberKind.PLAYER && r.getPlayers().get(m.playerId()).isRemoved()) {
            throw new ApiException(ErrorCode.NOT_ALLOWED);
        }
        CommandType command = CommandType.fromWire(type)
                .orElseThrow(() -> new ApiException(ErrorCode.BAD_MESSAGE, "Unknown message type."));
        command.action().ifPresent(action -> GameRuleUtils.require(role, action));
        commands.get(command).handle(new GameCommand(r, m, role, data, reply));
    }

    /** The command table (Command pattern): one handler per {@link CommandType}. */
    private Map<CommandType, CommandHandler> commandTable() {
        Map<CommandType, CommandHandler> table = new EnumMap<>(CommandType.class);
        table.put(CommandType.GAME_START, andReply(c -> lobby.startGame(c.room())));
        table.put(CommandType.GAME_NEXT, andReply(c -> next(c.room())));
        table.put(CommandType.GAME_AGAIN, andReply(c -> lobby.playAgain(c.room())));
        table.put(CommandType.GAME_END, andReply(c -> {
            if (!c.room().getPhase().inGame()) {
                throw new ApiException(ErrorCode.INVALID_PHASE);
            }
            enterFinale(c.room(), EndReason.ENDED_BY_HOST);
        }));
        table.put(CommandType.GAME_PAUSE, andReply(c -> pause(c.room(), PauseReason.OWNER)));
        table.put(CommandType.GAME_RESUME, andReply(c -> resume(c.room())));
        table.put(CommandType.GAME_SETTINGS, andReply(c -> lobby.changeSettings(c.room(), c.data())));
        table.put(CommandType.PAYWALL_DISMISS, andReply(c -> c.room().setPaywall(null)));
        table.put(CommandType.PLAYER_KICK, andReply(c -> lobby.kick(c.room(), c.text("playerId"))));
        table.put(
                CommandType.CAPTAIN_SET,
                andReply(c -> c.room()
                        .setCaptainId(GameRuleUtils.activePlayer(c.room(), c.text("playerId"))
                                .getId())));
        table.put(
                CommandType.ROOM_LOCK,
                andReply(c -> c.room().setLocked(c.data().path("locked").asBoolean(true))));
        table.put(CommandType.ROOM_CLOSE, c -> {
            c.reply().ok();
            closeLocked(c.room(), c.room().getPhase().inGame() ? EndReason.ENDED_BY_HOST : EndReason.COMPLETED);
        });
        table.put(CommandType.INTAKE_SUBMIT, c -> intake.submitIntake(c.room(), c.player(), c.data(), c.reply()));
        table.put(CommandType.DOSSIER_ADD, c -> intake.addSecret(c.room(), c.player(), c.data(), c.reply()));
        table.put(
                CommandType.ROUND_KIND_VOTE,
                andReply(c -> rounds.voteKind(c.room(), c.member().playerId(), c.text("kind"))));
        table.put(
                CommandType.ANSWER_SUBMIT,
                andReply(c -> voting.submitAnswer(c.room(), c.player(), c.text("duelId"), c.text("text"))));
        table.put(
                CommandType.VOTE_SUBMIT,
                andReply(c -> voting.vote(c.room(), c.member().playerId(), c.text("optionId"))));
        table.put(
                CommandType.AUDIENCE_VOTE,
                andReply(c -> voting.audienceVote(c.room(), c.member().audienceId(), c.text("optionId"))));
        table.put(CommandType.LINE_SKIP, andReply(c -> skipLine(c.room(), c.member(), c.role(), c.text("lineId"))));
        table.put(CommandType.FEEDBACK_QUICK, andReply(this::quickFeedback));
        if (table.size() != CommandType.values().length) {
            throw new IllegalStateException("Every command type needs a handler");
        }
        return table;
    }

    /** A handler that answers "ok" once the action is done. */
    private static CommandHandler andReply(Consumer<GameCommand> action) {
        return c -> {
            action.accept(c);
            c.reply().ok();
        };
    }

    private void quickFeedback(GameCommand c) {
        int score = c.data().path("score").asInt(0);
        if (score < 1 || score > 3) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Score must be 1, 2 or 3.");
        }
        analytics.track(
                "feedback_submitted",
                c.room().getCode() + ":" + c.member().playerId(),
                Map.of("source", "player", "score", score));
    }

    // ------------------------------------------------------------------ plumbing

    /** Applies a change under the room lock, then schedules a snapshot and the next timer. */
    public void mutate(RoomState room, Consumer<RoomState> change) {
        room.call(r -> {
            change.accept(r);
            r.addVersion(1);
            r.setLastActivity(clock.instant());
            reschedule(r);
            return null;
        });
        events.changed(room);
    }

    /** Runs slow work off the lock, then applies its result as a new command (skipped once the room closed). */
    @Override
    public <T> void async(RoomState room, Supplier<T> work, BiConsumer<RoomState, AsyncOutcome<T>> then) {
        io.execute(() -> {
            AsyncOutcome<T> outcome;
            try {
                outcome = new AsyncOutcome<>(work.get(), null);
            } catch (RuntimeException e) {
                if (!(e instanceof ApiException)) {
                    log.warn("Background task for room {} failed", room.getCode(), e);
                }
                outcome = new AsyncOutcome<>(null, e);
            }
            AsyncOutcome<T> result = outcome;
            if (registry.find(room.getCode()).orElse(null) == room) {
                mutate(room, r -> {
                    if (r.getPhase() != Phase.CLOSED) {
                        then.accept(r, result);
                    }
                });
            }
        });
    }

    void background(Runnable task) {
        io.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.warn("Background write failed", e);
            }
        });
    }

    @Override
    public HostAiService.CallContext ctx(RoomState r) {
        return new HostAiService.CallContext(
                r.getSessionId(), r.isFreeGame(), r.getLlmCalls(), r.getTtsCalls(), r.getModerationCalls());
    }

    @Override
    public long now() {
        return clock.millis();
    }

    void reschedule(RoomState r) {
        long next = nextWake(r);
        if (next == Long.MAX_VALUE) {
            if (r.getTick() != null) {
                r.getTick().cancel(false);
                r.setTick(null);
                r.setTickAtMs(Long.MAX_VALUE);
            }
            return;
        }
        if (r.getTick() != null && !r.getTick().isDone() && r.getTickAtMs() <= next) {
            return;
        }
        if (r.getTick() != null) {
            r.getTick().cancel(false);
        }
        r.setTickAtMs(next);
        long delay = Math.max(0, next - now()) + 5;
        r.setTick(scheduler.schedule(
                () -> mutate(r, x -> {
                    x.setTick(null);
                    x.setTickAtMs(Long.MAX_VALUE);
                    tick(x);
                }),
                delay,
                TimeUnit.MILLISECONDS));
    }

    long nextWake(RoomState r) {
        if (r.getPhase() == Phase.CLOSED) {
            return Long.MAX_VALUE;
        }
        long next = Long.MAX_VALUE;
        if (r.getDeadlineMs() != null && r.getPause() == null) {
            next = Math.min(next, r.getDeadlineMs());
        }
        if (r.getThinkingUntilMs() != null) {
            next = Math.min(next, r.getThinkingUntilMs());
        }
        if (r.getWaitDeadlineMs() != null) {
            next = Math.min(next, r.getWaitDeadlineMs());
        }
        if (r.getPhase() == Phase.INTAKE
                && r.getPause() == null
                && r.getDeadlineMs() != null
                && !r.getContentRequested().contains(GameRuleUtils.contentKey(r, 1))) {
            next = Math.min(next, r.getDeadlineMs() - props.intake().toMillis() / 3);
        }
        long grace = props.disconnectGrace().toMillis();
        for (PlayerState p : r.getPlayers().values()) {
            if (!p.isRemoved() && p.getConnections() == 0 && p.getDisconnectedAt() != null) {
                long at = p.getDisconnectedAt().toEpochMilli() + grace;
                if (at > now()) {
                    next = Math.min(next, at);
                }
            }
        }
        if (r.getOwnerScreenLostAt() != null
                && r.getPause() == null
                && r.getPhase().inGame()) {
            next = Math.min(
                    next,
                    r.getOwnerScreenLostAt().toEpochMilli()
                            + props.screenAutoPause().toMillis());
        }
        return next;
    }

    void tick(RoomState r) {
        if (r.getPhase() == Phase.CLOSED) {
            return;
        }
        long now = now();
        Instant nowInstant = clock.instant();
        if (r.getCaptainId() == null
                || !r.getPlayers().get(r.getCaptainId()).present(nowInstant, props.disconnectGrace())) {
            r.activePlayers().stream()
                    .filter(p -> p.present(nowInstant, props.disconnectGrace()))
                    .findFirst()
                    .ifPresent(p -> r.setCaptainId(p.getId()));
        }
        if (r.getOwnerScreenLostAt() != null
                && r.getPause() == null
                && r.getPhase().inGame()
                && now
                        >= r.getOwnerScreenLostAt().toEpochMilli()
                                + props.screenAutoPause().toMillis()) {
            pause(r, PauseReason.SCREEN_LOST);
        }
        checkPlayerCount(r);
        if (r.getPause() == PauseReason.WAITING_FOR_PLAYERS
                && r.getWaitDeadlineMs() != null
                && now >= r.getWaitDeadlineMs()) {
            r.setWaitDeadlineMs(null);
            r.setPause(null);
            enterFinale(r, EndReason.NOT_ENOUGH_PLAYERS);
            return;
        }
        if (r.getPending() != null && r.getThinkingUntilMs() != null && now >= r.getThinkingUntilMs()) {
            rounds.resolvePending(r, true);
        }
        if (r.getPhase() == Phase.INTAKE
                && r.getPause() == null
                && r.getDeadlineMs() != null
                && now >= r.getDeadlineMs() - props.intake().toMillis() / 3) {
            rounds.requestContent(r, 1);
        }
        if (r.getPause() == null && r.getPending() == null && r.getDeadlineMs() != null && now >= r.getDeadlineMs()) {
            r.setDeadlineMs(null);
            onDeadline(r);
        } else if (r.getPhase() == Phase.ANSWERING && r.getPause() == null) {
            if (voting.allAnswered(r)) {
                voting.endAnswering(r);
            }
        } else if (r.getPhase() == Phase.VOTING && r.getPause() == null) {
            VoteStepState step = r.getRound() == null ? null : r.getRound().currentVote();
            if (step != null && voting.votingComplete(r, step)) {
                voting.reveal(r);
            }
        }
    }

    void onDeadline(RoomState r) {
        PhaseHandler phase = phases.get(r.getPhase());
        if (phase != null) {
            phase.finish(r);
        }
    }

    /** Owner or captain skips ahead ("Next", "Don't wait", "Skip phase"). */
    void next(RoomState r) {
        if (r.getPending() != null) {
            rounds.resolvePending(r, true);
            return;
        }
        PhaseHandler phase = phases.get(r.getPhase());
        if (phase == null) {
            throw new ApiException(ErrorCode.INVALID_PHASE);
        }
        phase.finish(r);
    }

    void pause(RoomState r, PauseReason reason) {
        if (r.getPause() != null && r.getPause() != PauseReason.WAITING_FOR_PLAYERS) {
            return;
        }
        if (!r.getPhase().inGame()) {
            throw new ApiException(ErrorCode.INVALID_PHASE, "Nothing to pause right now.");
        }
        if (r.getPause() == null && r.getDeadlineMs() != null) {
            r.setPausedRemainingMs(Math.max(1000, r.getDeadlineMs() - now()));
            r.setDeadlineMs(null);
        } else if (r.getPause() == null) {
            r.setPausedRemainingMs(0);
        }
        r.setPause(reason);
        r.setWelcomeBack(false);
    }

    void resume(RoomState r) {
        if (r.getPause() == null || r.getPause() == PauseReason.WAITING_FOR_PLAYERS) {
            throw new ApiException(ErrorCode.INVALID_PHASE, "The game isn't paused.");
        }
        unpause(r);
    }

    void unpause(RoomState r) {
        r.setPause(null);
        r.setWelcomeBack(false);
        r.setWaitDeadlineMs(null);
        if (r.getPausedRemainingMs() > 0) {
            r.setDeadlineMs(now() + r.getPausedRemainingMs());
        }
        r.setPausedRemainingMs(0);
    }

    /** Fewer than 3 players present during a game: freeze and wait up to 60 seconds (user flow S7). */
    @Override
    public void checkPlayerCount(RoomState r) {
        if (!r.getPhase().inGame()) {
            return;
        }
        long present = presentPlayers(r).size();
        if (present < props.minPlayers() && r.getPause() == null) {
            pause(r, PauseReason.WAITING_FOR_PLAYERS);
            r.setWaitDeadlineMs(now() + props.waitForPlayers().toMillis());
        } else if (present >= props.minPlayers() && r.getPause() == PauseReason.WAITING_FOR_PLAYERS) {
            unpause(r);
        }
    }

    @Override
    public List<PlayerState> presentPlayers(RoomState r) {
        return r.presentPlayers(clock.instant(), props.disconnectGrace());
    }

    // ------------------------------------------------------------------ host lines

    @Override
    public String line(RoomState r, String kind, Map<String, String> values) {
        String text = fallback.line(r.getSettings().language(), kind, random);
        for (Map.Entry<String, String> e : values.entrySet()) {
            text = text.replace("{" + e.getKey() + "}", e.getValue());
        }
        return text;
    }

    /** Shows a host line and asks for its audio in the background. */
    @Override
    public void say(RoomState r, String text, Set<String> about) {
        r.addLineCounter(1);
        HostLine line = new HostLine("l" + r.getLineCounter(), text, null, Set.copyOf(about), false);
        r.setHostLine(line);
        HostAiService.CallContext ctx = ctx(r);
        async(r, () -> ai.speak(ctx, text).orElse(null), (room, audio) -> {
            if (audio.value() != null
                    && room.getHostLine() != null
                    && room.getHostLine().id().equals(line.id())
                    && !room.getHostLine().skipped()) {
                room.setHostLine(room.getHostLine().withAudio(audio.value()));
            }
        });
    }

    void skipLine(RoomState r, Member m, Role role, String lineId) {
        HostLine current = r.getHostLine();
        if (current == null || !current.id().equals(lineId) || current.skipped()) {
            throw new ApiException(ErrorCode.INVALID_PHASE, "That line is already gone.");
        }
        boolean own = m.playerId() != null && current.aboutPlayerIds().contains(m.playerId());
        if (!(Action.SKIP_ANY_LINE.allowedFor(role) || Action.SKIP_OWN_LINE.allowedFor(role) && own)) {
            throw new ApiException(ErrorCode.NOT_ALLOWED);
        }
        r.setHostLine(new HostLine(current.id(), line(r, "skipped", Map.of()), null, Set.of(), true));
        ModerationAction action =
                role == Role.OWNER_SCREEN ? ModerationAction.SKIPPED_BY_OWNER : ModerationAction.SKIPPED_BY_PLAYER;
        recordModeration(r.getSessionId(), ModerationStage.AI_OUTPUT, "host_line", action);
        analytics.track("line_skipped", r.getCode(), Map.of("by", role.name()));
    }

    @Override
    public void recordModeration(UUID sessionId, ModerationStage stage, String category, ModerationAction action) {
        Instant at = clock.instant();
        background(() -> moderationEvents.insert(sessionId, stage, category, action, at));
    }

    @Override
    public void enterFinale(RoomState r, EndReason reason) {
        r.setPhase(Phase.FINALE);
        r.setDeadlineMs(null);
        r.setPause(null);
        r.setWaitDeadlineMs(null);
        r.setPending(null);
        r.setThinkingUntilMs(null);
        finishSession(r, reason);
        if (r.getFinale() == null) {
            if (r.isFinaleRequested() && reason == EndReason.COMPLETED) {
                r.setPending(RoomState.Pending.FINALE);
                r.setThinkingUntilMs(now() + props.hostThinking().toMillis());
                return;
            }
            r.setFinale(finale.fallbackFinale(r));
        }
        announceFinale(r);
    }

    @Override
    public void announceFinale(RoomState r) {
        String speech = r.getFinale().speech();
        say(r, speech, Set.of());
        analytics.track(
                "game_completed",
                r.getOwnerUserId().toString(),
                Map.of(
                        "rounds",
                        r.getRoundNumber(),
                        "players",
                        r.activePlayers().size(),
                        "finale",
                        r.getFinale().source()));
    }

    void finishSession(RoomState r, EndReason reason) {
        if (r.getSessionId() == null) {
            return;
        }
        UUID id = r.getSessionId();
        r.setLastSessionId(id);
        r.setSessionId(null);
        GameSessionRepository.Finish finish = new GameSessionRepository.Finish(
                r.activePlayers().size(),
                r.getAudiencePeak(),
                Math.max(
                        0,
                        r.getPhase() == Phase.FINALE && reason == EndReason.COMPLETED
                                ? r.getRoundNumber()
                                : r.getRoundNumber() - 1),
                (int) r.getDossier().stream().count(),
                reason,
                clock.instant());
        background(() -> sessions.finish(id, finish));
    }

    /** Closes a room from outside its lock (idle sweep, admin, a newer room by the same host). */
    public void close(RoomState room, EndReason reason) {
        mutate(room, r -> {
            if (r.getPhase() != Phase.CLOSED) {
                closeLocked(r, reason);
            }
        });
    }

    void closeLocked(RoomState r, EndReason reason) {
        if (r.getPhase().inGame()) {
            finishSession(r, reason);
        }
        r.setPhase(Phase.CLOSED);
        r.setDeadlineMs(null);
        r.setPending(null);
        r.setThinkingUntilMs(null);
        r.setClosedAt(clock.instant());
        r.clearDossier();
        for (PlayerState p : r.getPlayers().values()) {
            Arrays.fill(p.getIntake(), null);
        }
        events.closed(r);
    }

    public LiveStatsDto liveStats() {
        int rooms = 0;
        int inGame = 0;
        int players = 0;
        int viewers = 0;
        for (RoomState room : List.copyOf(registry.all())) {
            int[] s = room.call(r -> new int[] {
                r.getPhase() == Phase.CLOSED ? 0 : 1,
                r.getPhase() != Phase.CLOSED && r.getPhase() != Phase.LOBBY ? 1 : 0,
                r.getPhase() == Phase.CLOSED ? 0 : r.activePlayers().size(),
                r.getPhase() == Phase.CLOSED ? 0 : r.getAudienceCount()
            });
            rooms += s[0];
            inGame += s[1];
            players += s[2];
            viewers += s[3];
        }
        return new LiveStatsDto(rooms, inGame, players, viewers);
    }

    /** Closes every open room of a host, e.g. when the account is deleted. */
    public void closeRoomsOf(UUID ownerUserId) {
        registry.all().stream()
                .filter(r -> ownerUserId.equals(r.getOwnerUserId()))
                .forEach(r -> close(r, EndReason.ENDED_BY_HOST));
    }

    /** Closes idle and expired rooms and forgets closed ones (blueprint 4.6). */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void sweep() {
        Instant now = clock.instant();
        for (RoomState room : List.copyOf(registry.all())) {
            EndReason expire = room.call(r -> {
                if (r.getPhase() == Phase.CLOSED) {
                    if (r.getClosedAt().plus(props.closedRetention()).isBefore(now)) {
                        registry.remove(r, List.copyOf(r.getMembers().keySet()));
                    }
                    return null;
                }
                if (r.getCreatedAt().plus(props.maxAge()).isBefore(now)) {
                    return EndReason.MAX_AGE;
                }
                return r.getLastActivity().plus(props.idleClose()).isBefore(now) ? EndReason.IDLE : null;
            });
            if (expire != null) {
                close(room, expire);
            }
        }
    }

    /** Server shutdown: record every running game as ended. */
    @PreDestroy
    public void shutdown() {
        for (RoomState room : List.copyOf(registry.all())) {
            room.call(r -> {
                if (r.getPhase().inGame() && r.getSessionId() != null) {
                    sessions.finish(
                            r.getSessionId(),
                            new GameSessionRepository.Finish(
                                    r.activePlayers().size(),
                                    r.getAudiencePeak(),
                                    Math.max(0, r.getRoundNumber() - 1),
                                    r.getDossier().size(),
                                    EndReason.SHUTDOWN,
                                    clock.instant()));
                }
                return null;
            });
        }
    }

    // ---------------------------------------------------------------- transitions, routed to the owning phase

    @Override
    public void startRound(RoomState r, int number) {
        rounds.startRound(r, number);
    }

    @Override
    public void startDuel(RoomState r, int index) {
        voting.startDuel(r, index);
    }

    @Override
    public void resolvePending(RoomState r, boolean timedOut) {
        rounds.resolvePending(r, timedOut);
    }

    @Override
    public void requestFinale(RoomState r) {
        finale.requestFinale(r);
    }

    @Override
    public Finale fallbackFinale(RoomState r) {
        return finale.fallbackFinale(r);
    }
}
