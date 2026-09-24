package com.insidejoke.game;

/** Two players answering the same prompt within an ANSWER_DUEL round. */
final class DuelState {

    private final String id;
    private final String prompt;
    private final String playerA;
    private final String playerB;
    private String answerA;
    private String answerB;
    private boolean blockedA;
    private boolean blockedB;
    private String reaction;
    private VoteStepState vote;
    private int pointsA;
    private int pointsB;

    DuelState(String id, String prompt, String playerA, String playerB) {
        this.id = id;
        this.prompt = prompt;
        this.playerA = playerA;
        this.playerB = playerB;
    }

    boolean involves(String playerId) {
        return playerA.equals(playerId) || playerB.equals(playerId);
    }

    String answerOf(String playerId) {
        return playerA.equals(playerId) ? answerA : answerB;
    }

    boolean usableA() {
        return answerA != null && !blockedA;
    }

    boolean usableB() {
        return answerB != null && !blockedB;
    }

    // ---------------------------------------------------------------- access

    public String getId() {
        return id;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getPlayerA() {
        return playerA;
    }

    public String getPlayerB() {
        return playerB;
    }

    public String getAnswerA() {
        return answerA;
    }

    public void setAnswerA(String answerA) {
        this.answerA = answerA;
    }

    public String getAnswerB() {
        return answerB;
    }

    public void setAnswerB(String answerB) {
        this.answerB = answerB;
    }

    public boolean isBlockedA() {
        return blockedA;
    }

    public void setBlockedA(boolean blockedA) {
        this.blockedA = blockedA;
    }

    public boolean isBlockedB() {
        return blockedB;
    }

    public void setBlockedB(boolean blockedB) {
        this.blockedB = blockedB;
    }

    public String getReaction() {
        return reaction;
    }

    public void setReaction(String reaction) {
        this.reaction = reaction;
    }

    public VoteStepState getVote() {
        return vote;
    }

    public void setVote(VoteStepState vote) {
        this.vote = vote;
    }

    public int getPointsA() {
        return pointsA;
    }

    public void setPointsA(int pointsA) {
        this.pointsA = pointsA;
    }

    public void addPointsA(int delta) {
        this.pointsA += delta;
    }

    public int getPointsB() {
        return pointsB;
    }

    public void setPointsB(int pointsB) {
        this.pointsB = pointsB;
    }

    public void addPointsB(int delta) {
        this.pointsB += delta;
    }
}
