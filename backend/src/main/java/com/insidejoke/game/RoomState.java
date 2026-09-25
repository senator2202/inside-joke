package com.insidejoke.game;

import com.insidejoke.common.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * One party, entirely in memory (blueprint 4.1). Every read and write of the mutable fields happens under
 * {@link #lock}; the engine guarantees that no network call runs while it is held.
 */
public final class RoomState {

    /** What the room is waiting for while the host "is thinking" (at most a few seconds, then fallback). */
    enum Pending {
        ROUND_CONTENT,
        DUEL_REVIEW,
        FINALE
    }

    private final String code;
    private final UUID ownerUserId;
    private final String ownerToken;
    /**
     * Separate, unguessable key for the viewers' link (/w/{key}). The room code never appears in anything shown to a
     * stream, so a hidden code stays hidden even though the viewer link is on screen.
     */
    private final String audienceKey;

    private final Instant createdAt;
    private final ReentrantLock lock = new ReentrantLock();

    private RoomSettings settings;
    private boolean locked;
    private Phase phase = Phase.LOBBY;
    private Long deadlineMs;
    private PauseReason pause;
    private long pausedRemainingMs;
    private Long waitDeadlineMs;
    private Long thinkingUntilMs;
    private Pending pending;
    private RoundKind pendingKind;
    private boolean welcomeBack;
    private Instant closedAt;

    private final Map<String, PlayerState> players = new LinkedHashMap<>();
    private String captainId;
    private final Map<String, Member> members = new HashMap<>();
    /** Remote screen copies by token; at most {@link GameProperties#maxScreenCopies()} are held at a time. */
    private final Map<String, ScreenCopyState> screenCopies = new HashMap<>();
    /** Recent requests per member token, to answer re-sent copies; has its own lock, usable without the room's. */
    private final RequestHistoryState requests = new RequestHistoryState();

    private final List<DossierFact> dossier = new ArrayList<>();
    private List<String> intakeQuestions = List.of();

    private int gameNumber;
    private int roundNumber;
    private int roundsTotal;
    private RoundState round;
    private final EnumMap<RoundKind, Integer> kindCounts = new EnumMap<>(RoundKind.class);
    private final Map<String, RoundKind> kindVotes = new LinkedHashMap<>();
    private final Map<Integer, RoundContent> content = new HashMap<>();
    private final Set<Integer> contentRequested = new HashSet<>();
    private final Set<String> recentPrompts = new HashSet<>();
    private boolean reviewPending;

    private UUID sessionId;
    private UUID lastSessionId;
    private boolean starting;
    private ErrorCode paywall;
    private boolean freeGame;

    private HostLine hostLine;
    private int lineCounter;
    private Finale finale;
    private boolean finaleRequested;
    private String answerOfNightText;
    private String answerOfNightPrompt;
    private String answerOfNightAuthor;
    private int answerOfNightVotes = -1;

    private final AtomicInteger llmCalls = new AtomicInteger();
    private final AtomicInteger ttsCalls = new AtomicInteger();
    private final AtomicInteger moderationCalls = new AtomicInteger();
    private int audienceCount;
    private int audiencePeak;
    private int ownerScreens;
    private Instant ownerScreenLostAt;

    private Instant lastActivity;
    private long version;
    private ScheduledFuture<?> tick;
    private long tickAtMs = Long.MAX_VALUE;
    private int idCounter;

    RoomState(
            String code, String audienceKey, UUID ownerUserId, String ownerToken, RoomSettings settings, Instant now) {
        this.code = code;
        this.audienceKey = audienceKey;
        this.ownerUserId = ownerUserId;
        this.ownerToken = ownerToken;
        this.settings = settings;
        this.createdAt = now;
        this.lastActivity = now;
    }

    /** Runs {@code fn} under the room lock. Callers must not perform network I/O inside. */
    public <T> T call(Function<RoomState, T> fn) {
        lock.lock();
        try {
            return fn.apply(this);
        } finally {
            lock.unlock();
        }
    }

    String nextId(String prefix) {
        idCounter++;
        return prefix + idCounter;
    }

    List<PlayerState> activePlayers() {
        return players.values().stream().filter(p -> !p.isRemoved()).toList();
    }

    Role roleOf(Member m) {
        return switch (m.kind()) {
            case OWNER_SCREEN -> Role.OWNER_SCREEN;
            case SCREEN -> Role.SCREEN;
            case AUDIENCE -> Role.AUDIENCE;
            case PLAYER -> m.playerId().equals(captainId) ? Role.CAPTAIN : Role.PLAYER;
        };
    }

    /** Active players by score, highest first; earlier joiners first on a tie. */
    public List<PlayerState> standings() {
        return activePlayers().stream()
                .sorted(Comparator.comparingInt((PlayerState p) -> -p.getScore())
                        .thenComparing(PlayerState::getJoinedAt))
                .toList();
    }

    /** 1 for the leader; players on the same score share a place. */
    public int rankOf(PlayerState player) {
        return 1
                + (int) activePlayers().stream()
                        .filter(o -> o.getScore() > player.getScore())
                        .count();
    }

    /** Active players connected now, or disconnected for less than {@code grace}. */
    public List<PlayerState> presentPlayers(Instant now, Duration grace) {
        return activePlayers().stream().filter(p -> p.present(now, grace)).toList();
    }

    /** Whether this viewer has voted on the current step. */
    public boolean audienceVoted(String audienceId) {
        VoteStepState step = round == null ? null : round.currentVote();
        return step != null && step.getAudienceVoters().contains(audienceId);
    }

    // ---------------------------------------------------------------- access

    public String getCode() {
        return code;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public String getOwnerToken() {
        return ownerToken;
    }

    public String getAudienceKey() {
        return audienceKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public RoomSettings getSettings() {
        return settings;
    }

    public void setSettings(RoomSettings settings) {
        this.settings = settings;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
    }

    public Long getDeadlineMs() {
        return deadlineMs;
    }

    public void setDeadlineMs(Long deadlineMs) {
        this.deadlineMs = deadlineMs;
    }

    public PauseReason getPause() {
        return pause;
    }

    public void setPause(PauseReason pause) {
        this.pause = pause;
    }

    public long getPausedRemainingMs() {
        return pausedRemainingMs;
    }

    public void setPausedRemainingMs(long pausedRemainingMs) {
        this.pausedRemainingMs = pausedRemainingMs;
    }

    public Long getWaitDeadlineMs() {
        return waitDeadlineMs;
    }

    public void setWaitDeadlineMs(Long waitDeadlineMs) {
        this.waitDeadlineMs = waitDeadlineMs;
    }

    public Long getThinkingUntilMs() {
        return thinkingUntilMs;
    }

    public void setThinkingUntilMs(Long thinkingUntilMs) {
        this.thinkingUntilMs = thinkingUntilMs;
    }

    Pending getPending() {
        return pending;
    }

    void setPending(Pending pending) {
        this.pending = pending;
    }

    public RoundKind getPendingKind() {
        return pendingKind;
    }

    public void setPendingKind(RoundKind pendingKind) {
        this.pendingKind = pendingKind;
    }

    public boolean isWelcomeBack() {
        return welcomeBack;
    }

    public void setWelcomeBack(boolean welcomeBack) {
        this.welcomeBack = welcomeBack;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public Map<String, PlayerState> getPlayers() {
        return Collections.unmodifiableMap(players);
    }

    public void putPlayer(String key, PlayerState value) {
        players.put(key, value);
    }

    public String getCaptainId() {
        return captainId;
    }

    public void setCaptainId(String captainId) {
        this.captainId = captainId;
    }

    public Map<String, Member> getMembers() {
        return Collections.unmodifiableMap(members);
    }

    RequestHistoryState getRequests() {
        return requests;
    }

    public void putMember(String key, Member value) {
        members.put(key, value);
    }

    Map<String, ScreenCopyState> getScreenCopies() {
        return Collections.unmodifiableMap(screenCopies);
    }

    void putScreenCopy(String token, ScreenCopyState copy) {
        members.put(token, new Member(token, MemberKind.SCREEN, null, null));
        screenCopies.put(token, copy);
    }

    /** Takes back a screen copy's token: the member, its state and the requests it sent. */
    void removeScreenCopy(String token) {
        members.remove(token);
        screenCopies.remove(token);
        requests.forgetMember(token);
    }

    /**
     * The screen copy that has had no socket for the longest time, if that began no later than {@code idleBefore}:
     * the one whose token can be given to someone else.
     */
    Optional<String> longestIdleScreenCopy(Instant idleBefore) {
        return screenCopies.entrySet().stream()
                .filter(e -> e.getValue().getIdleSince() != null
                        && !e.getValue().getIdleSince().isAfter(idleBefore))
                .min(Comparator.comparing(e -> e.getValue().getIdleSince()))
                .map(Map.Entry::getKey);
    }

    public List<DossierFact> getDossier() {
        return Collections.unmodifiableList(dossier);
    }

    public void addDossier(DossierFact value) {
        dossier.add(value);
    }

    public void clearDossier() {
        dossier.clear();
    }

    public List<String> getIntakeQuestions() {
        return Collections.unmodifiableList(intakeQuestions);
    }

    public void setIntakeQuestions(List<String> intakeQuestions) {
        this.intakeQuestions = intakeQuestions;
    }

    public int getGameNumber() {
        return gameNumber;
    }

    public void addGameNumber(int delta) {
        this.gameNumber += delta;
    }

    public int getRoundNumber() {
        return roundNumber;
    }

    public void setRoundNumber(int roundNumber) {
        this.roundNumber = roundNumber;
    }

    public int getRoundsTotal() {
        return roundsTotal;
    }

    public void setRoundsTotal(int roundsTotal) {
        this.roundsTotal = roundsTotal;
    }

    RoundState getRound() {
        return round;
    }

    void setRound(RoundState round) {
        this.round = round;
    }

    public Map<RoundKind, Integer> getKindCounts() {
        return Collections.unmodifiableMap(kindCounts);
    }

    public void clearKindCount() {
        kindCounts.clear();
    }

    /** One more round of {@code kind} played this game. */
    public void countKind(RoundKind kind) {
        kindCounts.merge(kind, 1, Integer::sum);
    }

    public Map<String, RoundKind> getKindVotes() {
        return Collections.unmodifiableMap(kindVotes);
    }

    public void clearKindVote() {
        kindVotes.clear();
    }

    public void putKindVote(String key, RoundKind value) {
        kindVotes.put(key, value);
    }

    public Map<Integer, RoundContent> getContent() {
        return Collections.unmodifiableMap(content);
    }

    public void putIfAbsentContent(Integer key, RoundContent value) {
        content.putIfAbsent(key, value);
    }

    public Set<Integer> getContentRequested() {
        return Collections.unmodifiableSet(contentRequested);
    }

    public boolean addContentRequested(Integer value) {
        return contentRequested.add(value);
    }

    public Set<String> getRecentPrompts() {
        return Collections.unmodifiableSet(recentPrompts);
    }

    public void addRecentPrompt(String value) {
        recentPrompts.add(value);
    }

    public boolean isReviewPending() {
        return reviewPending;
    }

    public void setReviewPending(boolean reviewPending) {
        this.reviewPending = reviewPending;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public UUID getLastSessionId() {
        return lastSessionId;
    }

    public void setLastSessionId(UUID lastSessionId) {
        this.lastSessionId = lastSessionId;
    }

    public boolean isStarting() {
        return starting;
    }

    public void setStarting(boolean starting) {
        this.starting = starting;
    }

    public ErrorCode getPaywall() {
        return paywall;
    }

    public void setPaywall(ErrorCode paywall) {
        this.paywall = paywall;
    }

    public boolean isFreeGame() {
        return freeGame;
    }

    public void setFreeGame(boolean freeGame) {
        this.freeGame = freeGame;
    }

    public HostLine getHostLine() {
        return hostLine;
    }

    public void setHostLine(HostLine hostLine) {
        this.hostLine = hostLine;
    }

    public int getLineCounter() {
        return lineCounter;
    }

    public void addLineCounter(int delta) {
        this.lineCounter += delta;
    }

    public Finale getFinale() {
        return finale;
    }

    public void setFinale(Finale finale) {
        this.finale = finale;
    }

    public boolean isFinaleRequested() {
        return finaleRequested;
    }

    public void setFinaleRequested(boolean finaleRequested) {
        this.finaleRequested = finaleRequested;
    }

    public String getAnswerOfNightText() {
        return answerOfNightText;
    }

    public void setAnswerOfNightText(String answerOfNightText) {
        this.answerOfNightText = answerOfNightText;
    }

    public String getAnswerOfNightPrompt() {
        return answerOfNightPrompt;
    }

    public void setAnswerOfNightPrompt(String answerOfNightPrompt) {
        this.answerOfNightPrompt = answerOfNightPrompt;
    }

    public String getAnswerOfNightAuthor() {
        return answerOfNightAuthor;
    }

    public void setAnswerOfNightAuthor(String answerOfNightAuthor) {
        this.answerOfNightAuthor = answerOfNightAuthor;
    }

    public int getAnswerOfNightVotes() {
        return answerOfNightVotes;
    }

    public void setAnswerOfNightVotes(int answerOfNightVotes) {
        this.answerOfNightVotes = answerOfNightVotes;
    }

    public AtomicInteger getLlmCalls() {
        return llmCalls;
    }

    public AtomicInteger getTtsCalls() {
        return ttsCalls;
    }

    public AtomicInteger getModerationCalls() {
        return moderationCalls;
    }

    public int getAudienceCount() {
        return audienceCount;
    }

    public void setAudienceCount(int audienceCount) {
        this.audienceCount = audienceCount;
    }

    public void addAudienceCount(int delta) {
        this.audienceCount += delta;
    }

    public int getAudiencePeak() {
        return audiencePeak;
    }

    public void setAudiencePeak(int audiencePeak) {
        this.audiencePeak = audiencePeak;
    }

    public int getOwnerScreens() {
        return ownerScreens;
    }

    public void setOwnerScreens(int ownerScreens) {
        this.ownerScreens = ownerScreens;
    }

    public void addOwnerScreens(int delta) {
        this.ownerScreens += delta;
    }

    public Instant getOwnerScreenLostAt() {
        return ownerScreenLostAt;
    }

    public void setOwnerScreenLostAt(Instant ownerScreenLostAt) {
        this.ownerScreenLostAt = ownerScreenLostAt;
    }

    public Instant getLastActivity() {
        return lastActivity;
    }

    public void setLastActivity(Instant lastActivity) {
        this.lastActivity = lastActivity;
    }

    public long getVersion() {
        return version;
    }

    public void addVersion(long delta) {
        this.version += delta;
    }

    public ScheduledFuture<?> getTick() {
        return tick;
    }

    public void setTick(ScheduledFuture<?> tick) {
        this.tick = tick;
    }

    public long getTickAtMs() {
        return tickAtMs;
    }

    public void setTickAtMs(long tickAtMs) {
        this.tickAtMs = tickAtMs;
    }
}
