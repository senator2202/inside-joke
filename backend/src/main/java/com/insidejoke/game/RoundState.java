package com.insidejoke.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

final class RoundState {

    private final int number;
    private final RoundKind kind;
    private final List<DuelState> duels = new ArrayList<>();
    private int duelIndex;

    private String question;
    private String subjectId;
    private String statement;
    private boolean statementTrue;
    private String truthLine;
    private String fakeLine;
    private VoteStepState vote;
    private Map<String, Integer> points = new HashMap<>();

    private long answeringStartedMs;

    RoundState(int number, RoundKind kind) {
        this.number = number;
        this.kind = kind;
    }

    DuelState currentDuel() {
        return kind == RoundKind.ANSWER_DUEL && duelIndex < duels.size() ? duels.get(duelIndex) : null;
    }

    VoteStepState currentVote() {
        DuelState d = currentDuel();
        return d != null ? d.getVote() : vote;
    }

    // ---------------------------------------------------------------- access

    public int getNumber() {
        return number;
    }

    public RoundKind getKind() {
        return kind;
    }

    public List<DuelState> getDuels() {
        return Collections.unmodifiableList(duels);
    }

    public boolean addDuel(DuelState value) {
        return duels.add(value);
    }

    public int getDuelIndex() {
        return duelIndex;
    }

    public void setDuelIndex(int duelIndex) {
        this.duelIndex = duelIndex;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(String subjectId) {
        this.subjectId = subjectId;
    }

    public String getStatement() {
        return statement;
    }

    public void setStatement(String statement) {
        this.statement = statement;
    }

    public boolean isStatementTrue() {
        return statementTrue;
    }

    public void setStatementTrue(boolean statementTrue) {
        this.statementTrue = statementTrue;
    }

    public String getTruthLine() {
        return truthLine;
    }

    public void setTruthLine(String truthLine) {
        this.truthLine = truthLine;
    }

    public String getFakeLine() {
        return fakeLine;
    }

    public void setFakeLine(String fakeLine) {
        this.fakeLine = fakeLine;
    }

    public VoteStepState getVote() {
        return vote;
    }

    public void setVote(VoteStepState vote) {
        this.vote = vote;
    }

    public Map<String, Integer> getPoints() {
        return Collections.unmodifiableMap(points);
    }

    public void setPoints(Map<String, Integer> points) {
        this.points = points;
    }

    public Integer mergePoint(
            String key, Integer value, BiFunction<? super Integer, ? super Integer, ? extends Integer> remap) {
        return points.merge(key, value, remap);
    }

    public long getAnsweringStartedMs() {
        return answeringStartedMs;
    }

    public void setAnsweringStartedMs(long answeringStartedMs) {
        this.answeringStartedMs = answeringStartedMs;
    }
}
