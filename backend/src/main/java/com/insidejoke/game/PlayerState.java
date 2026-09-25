package com.insidejoke.game;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

/** A guest in a room. Mutable, only touched under the room lock; nothing here is ever written to the database. */
public final class PlayerState {

    private final String id;
    private final Instant joinedAt;
    private final String name;
    private final String emoji;
    private int connections;
    private Instant disconnectedAt;
    private boolean removed;
    private int score;
    private final String[] intake = new String[3];
    private int factsAdded;
    /** Model checks of this player's secrets and intake answers in the current game (accepted or not). */
    private int moderationChecks;
    /** Game number (1 = first, then "play again") in which this player last completed the intake. */
    private int intakeGame;

    private int answersGiven;
    private long fastestAnswerMs = Long.MAX_VALUE;
    private int votesReceived;
    private int duelsWon;
    private int peopleFooled;
    private int correctGuesses;
    private int whoPicks;

    PlayerState(String id, String name, String emoji, Instant joinedAt) {
        this.id = id;
        this.name = name;
        this.emoji = emoji;
        this.joinedAt = joinedAt;
    }

    boolean connected() {
        return connections > 0;
    }

    /** Connected, or disconnected for less than the grace period: still counted as playing. */
    boolean present(Instant now, Duration grace) {
        return !removed
                && (connections > 0
                        || disconnectedAt == null
                        || disconnectedAt.plus(grace).isAfter(now));
    }

    int intakeAnswered() {
        return (int)
                Arrays.stream(intake).filter(a -> a != null && !a.isBlank()).count();
    }

    void resetForNewGame() {
        moderationChecks = 0;
        score = 0;
        answersGiven = 0;
        fastestAnswerMs = Long.MAX_VALUE;
        votesReceived = 0;
        duelsWon = 0;
        peopleFooled = 0;
        correctGuesses = 0;
        whoPicks = 0;
        factsAdded = 0;
    }

    /** Secrets this player may still add, out of {@code max} per game. */
    public int secretsLeft(int max) {
        return Math.max(0, max - factsAdded);
    }

    // ---------------------------------------------------------------- access

    public String getId() {
        return id;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public String getName() {
        return name;
    }

    public String getEmoji() {
        return emoji;
    }

    public int getConnections() {
        return connections;
    }

    public void setConnections(int connections) {
        this.connections = connections;
    }

    public void addConnections(int delta) {
        this.connections += delta;
    }

    public Instant getDisconnectedAt() {
        return disconnectedAt;
    }

    public void setDisconnectedAt(Instant disconnectedAt) {
        this.disconnectedAt = disconnectedAt;
    }

    public boolean isRemoved() {
        return removed;
    }

    public void setRemoved(boolean removed) {
        this.removed = removed;
    }

    public int getScore() {
        return score;
    }

    public void addScore(int delta) {
        this.score += delta;
    }

    public String[] getIntake() {
        return intake;
    }

    public int getFactsAdded() {
        return factsAdded;
    }

    public void addFactsAdded(int delta) {
        this.factsAdded += delta;
    }

    public int getModerationChecks() {
        return moderationChecks;
    }

    public void addModerationChecks(int delta) {
        this.moderationChecks += delta;
    }

    public int getIntakeGame() {
        return intakeGame;
    }

    public void setIntakeGame(int intakeGame) {
        this.intakeGame = intakeGame;
    }

    public int getAnswersGiven() {
        return answersGiven;
    }

    public void addAnswersGiven(int delta) {
        this.answersGiven += delta;
    }

    public long getFastestAnswerMs() {
        return fastestAnswerMs;
    }

    public void setFastestAnswerMs(long fastestAnswerMs) {
        this.fastestAnswerMs = fastestAnswerMs;
    }

    public int getVotesReceived() {
        return votesReceived;
    }

    public void addVotesReceived(int delta) {
        this.votesReceived += delta;
    }

    public int getDuelsWon() {
        return duelsWon;
    }

    public void addDuelsWon(int delta) {
        this.duelsWon += delta;
    }

    public int getPeopleFooled() {
        return peopleFooled;
    }

    public void addPeopleFooled(int delta) {
        this.peopleFooled += delta;
    }

    public int getCorrectGuesses() {
        return correctGuesses;
    }

    public void addCorrectGuesses(int delta) {
        this.correctGuesses += delta;
    }

    public int getWhoPicks() {
        return whoPicks;
    }

    public void addWhoPicks(int delta) {
        this.whoPicks += delta;
    }
}
