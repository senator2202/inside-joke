package com.insidejoke.game;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One thing to vote on: a duel, a "who of us" question or a truth-or-AI statement. */
final class VoteStepState {

    private final List<String> options;
    private final Set<String> eligible;
    private final Map<String, String> votes = new LinkedHashMap<>();
    private final Map<String, Integer> audience = new HashMap<>();
    private final Set<String> audienceVoters = new HashSet<>();

    VoteStepState(List<String> options, Set<String> eligible) {
        this.options = List.copyOf(options);
        this.eligible = eligible;
    }

    int countFor(String option) {
        return (int) votes.values().stream().filter(option::equals).count();
    }

    int audienceTotal() {
        return audience.values().stream().mapToInt(Integer::intValue).sum();
    }

    // ---------------------------------------------------------------- access

    public List<String> getOptions() {
        return options;
    }

    public Set<String> getEligible() {
        return Collections.unmodifiableSet(eligible);
    }

    public void removeEligible(String playerId) {
        eligible.remove(playerId);
    }

    public Map<String, String> getVotes() {
        return Collections.unmodifiableMap(votes);
    }

    public void putVote(String key, String value) {
        votes.put(key, value);
    }

    public Map<String, Integer> getAudience() {
        return Collections.unmodifiableMap(audience);
    }

    /** One more viewer's vote for {@code option}. */
    public void addAudienceVote(String option) {
        audience.merge(option, 1, Integer::sum);
    }

    public Set<String> getAudienceVoters() {
        return Collections.unmodifiableSet(audienceVoters);
    }

    public boolean addAudienceVoter(String value) {
        return audienceVoters.add(value);
    }
}
