package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RequestHistoryStateTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode VOTE_A = JSON.readTree("{\"optionId\":\"A\"}");
    private static final JsonNode VOTE_B = JSON.readTree("{\"optionId\":\"B\"}");

    private final RequestHistoryState history = new RequestHistoryState();

    /** A connection's answers, in order: "ok {data}" or "error CODE". */
    private static final class Answers implements ReplyHandler {
        final List<String> got = new ArrayList<>();

        @Override
        public void ok(Map<String, Object> data) {
            got.add("ok " + data);
        }

        @Override
        public void error(ApiException e) {
            got.add("error " + e.code());
        }
    }

    private Optional<RequestHistoryState.Tracked> vote(String token, String reqId, JsonNode data, Answers to) {
        return history.begin(token, reqId, "vote.submit", data, to);
    }

    @Test
    void aNewRequestRunsAndItsAnswerGoesToTheSender() {
        Answers first = new Answers();
        RequestHistoryState.Tracked run = vote("tok", "r1", VOTE_A, first).orElseThrow();
        run.ok(Map.of("n", 1));
        assertThat(first.got).containsExactly("ok {n=1}");
    }

    @Test
    void aCopyOfAnAnsweredRequestGetsTheSameAnswerWithoutRunning() {
        vote("tok", "r1", VOTE_A, new Answers()).orElseThrow().ok(Map.of("n", 1));
        vote("tok", "r2", VOTE_B, new Answers()).orElseThrow().error(new ApiException(ErrorCode.INVALID_PHASE));

        Answers again = new Answers();
        assertThat(vote("tok", "r1", VOTE_A, again)).isEmpty();
        assertThat(vote("tok", "r2", VOTE_B, again)).isEmpty();
        assertThat(again.got).containsExactly("ok {n=1}", "error INVALID_PHASE");
    }

    @Test
    void aCopyOfARunningRequestGetsTheAnswerWhenItComes() {
        Answers lost = new Answers();
        RequestHistoryState.Tracked run = vote("tok", "r1", VOTE_A, lost).orElseThrow();
        Answers reconnected = new Answers();
        assertThat(vote("tok", "r1", VOTE_A, reconnected)).isEmpty();
        assertThat(reconnected.got).isEmpty();

        run.ok(Map.of());
        run.error(new ApiException(ErrorCode.INTERNAL));
        assertThat(reconnected.got).as("answered once, on the live connection").containsExactly("ok {}");
        assertThat(lost.got).isEmpty();
    }

    @Test
    void theSameIdWithOtherContentOrFromAnotherMemberIsANewRequest() {
        vote("tok", "r1", VOTE_A, new Answers()).orElseThrow().ok(Map.of());
        assertThat(vote("tok", "r1", VOTE_B, new Answers())).isPresent();
        assertThat(history.begin("tok", "r1", "answer.submit", VOTE_B, new Answers()))
                .isPresent();
        assertThat(vote("other", "r1", VOTE_A, new Answers())).isPresent();
    }

    @Test
    void rateLimitedAndAbandonedRequestsRunAgain() {
        vote("tok", "r1", VOTE_A, new Answers()).orElseThrow().error(new ApiException(ErrorCode.RATE_LIMITED));
        assertThat(vote("tok", "r1", VOTE_A, new Answers()))
                .as("the client's retry runs")
                .isPresent();

        vote("tok", "r2", VOTE_A, new Answers()).orElseThrow().abandon();
        assertThat(vote("tok", "r2", VOTE_A, new Answers())).isPresent();
    }

    @Test
    void onlyTheLatestRequestsOfEachMemberAreKept() {
        for (int i = 0; i <= RequestHistoryState.PER_MEMBER; i++) {
            vote("tok", "r" + i, VOTE_A, new Answers()).orElseThrow().ok(Map.of());
        }
        assertThat(vote("tok", "r1", VOTE_A, new Answers()))
                .as("still remembered")
                .isEmpty();
        assertThat(vote("tok", "r0", VOTE_A, new Answers()))
                .as("the oldest is forgotten")
                .isPresent();
    }
}
