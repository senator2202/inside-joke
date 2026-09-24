package com.insidejoke.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/** Plays a game the way a group would: answers, votes, round-kind choices and "Next" on reveals. */
public final class Driver {

    private final Party party;
    private final List<String> kindChoices;
    private final List<JsonNode> screenSteps = new ArrayList<>();
    private Consumer<JsonNode> onStep = s -> {};

    public Driver(Party party, List<String> kindChoices) {
        this.party = party;
        this.kindChoices = new ArrayList<>(kindChoices);
    }

    public Driver onStep(Consumer<JsonNode> hook) {
        this.onStep = hook;
        return this;
    }

    public List<JsonNode> steps() {
        return screenSteps;
    }

    static String key(JsonNode s) {
        return s.path("phase").asString() + "/" + s.path("round").path("n").asInt() + "/"
                + s.path("round").path("duelIndex").asInt(-1) + "/"
                + s.path("thinking").asBoolean() + "/" + s.path("paused").isMissingNode();
    }

    /** Plays until the shared screen shows FINALE. */
    public JsonNode playToFinale() {
        for (int guard = 0; guard < 200; guard++) {
            JsonNode s = party.screen.state(x -> !x.path("thinking").asBoolean()
                    && !"LOBBY".equals(x.path("phase").asString()));
            String phase = s.path("phase").asString();
            if ("FINALE".equals(phase)) {
                return party.screen.state(x -> x.path("finale").path("ready").asBoolean());
            }
            screenSteps.add(s);
            onStep.accept(s);
            String key = key(s);
            switch (phase) {
                case "ANSWERING" -> answerAll(s.path("version").asLong());
                case "VOTING" -> voteAll(s);
                case "REVEAL" -> party.captain().socket().ok("game.next", Map.of());
                case "ROUND_VOTE" -> chooseKind();
                case "INTAKE" -> party.completeIntake();
                default -> throw new AssertionError("Unexpected phase " + phase);
            }
            party.screen.state(x -> !key(x).equals(key));
        }
        throw new AssertionError("Game did not finish");
    }

    private void answerAll(long version) {
        for (Party.Phone p : party.phones) {
            JsonNode st = p.socket().state(x -> x.path("version").asLong() >= version);
            if (!"ANSWERING".equals(st.path("phase").asString())) {
                return;
            }
            for (JsonNode a : st.path("you").path("assignments")) {
                if (a.path("answer").isMissingNode() || a.path("answer").isNull()) {
                    p.socket()
                            .ok(
                                    "answer.submit",
                                    Map.of(
                                            "duelId",
                                            a.path("duelId").asString(),
                                            "text",
                                            p.name() + " says: "
                                                    + a.path("prompt")
                                                            .asString()
                                                            .length()));
                }
            }
        }
    }

    private void voteAll(JsonNode screen) {
        String step = key(screen);
        String kind = screen.path("round").path("kind").asString();
        for (Party.Phone p : party.phones) {
            long version = screen.path("version").asLong();
            JsonNode st = p.socket().state(x -> x.path("version").asLong() >= version);
            if (!key(st).equals(step)) {
                return;
            }
            JsonNode you = st.path("you");
            if (you.path("canVote").asBoolean() && !you.path("voted").asBoolean()) {
                String option = switch (kind) {
                    case "ANSWER_DUEL" -> "A";
                    case "TRUTH_OR_AI" -> "truth";
                    default ->
                        st.path("round").path("options").get(0).path("id").asString();
                };
                p.socket().ok("vote.submit", Map.of("optionId", option));
            }
        }
    }

    private void chooseKind() {
        String kind = kindChoices.isEmpty() ? "WHO_OF_US" : kindChoices.removeFirst();
        for (Party.Phone p : party.phones) {
            p.socket().state(x -> "ROUND_VOTE".equals(x.path("phase").asString()));
            p.socket().ok("round.kind.vote", Map.of("kind", kind));
        }
        party.screen.ok("game.next", Map.of());
    }
}
