package com.insidejoke.game;

import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import java.io.Serial;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import tools.jackson.databind.JsonNode;

/**
 * The latest requests of each member of a room and their answers. A client re-sends the requests it had in flight when
 * its connection dropped, because it can't tell whether they arrived; such a copy is answered again instead of being
 * applied twice (a second secret, a "you already voted" for a vote that counted). A copy is the same {@code reqId}
 * with the same type and data from the same member; a copy of a request that is still running (a secret being checked)
 * gets the answer when it comes.
 *
 * <p>Guarded by its own lock, not by the room lock: an error answer can be sent after the room lock is released.
 * Answers are sent outside the lock.
 */
final class RequestHistoryState {

    /** Requests kept per member: a client only re-sends the few it had in flight when the connection dropped. */
    static final int PER_MEMBER = 16;

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Map<String, Call>> byMember = new HashMap<>();

    /**
     * Registers a request. Returns the handler to run it with: its answer is remembered and goes to whoever sent the
     * request last. Returns empty for a copy of a known request, which is answered through {@code reply} instead.
     */
    Optional<Tracked> begin(String memberToken, String reqId, String type, JsonNode data, ReplyHandler reply) {
        Call known;
        lock.lock();
        try {
            Map<String, Call> calls = byMember.computeIfAbsent(memberToken, k -> recentCalls());
            known = calls.get(reqId);
            if (known == null || !known.sameAs(type, data)) {
                Call call = new Call(type, data, reply);
                calls.put(reqId, call);
                return Optional.of(new Tracked(memberToken, reqId, call));
            }
            if (!known.done) {
                known.waiter = reply;
                return Optional.empty();
            }
        } finally {
            lock.unlock();
        }
        known.replay(reply);
        return Optional.empty();
    }

    private static Map<String, Call> recentCalls() {
        return new LinkedHashMap<>() {
            @Serial
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Call> eldest) {
                return size() > PER_MEMBER;
            }
        };
    }

    /** Drops everything remembered for a member whose token was taken back. */
    void forgetMember(String memberToken) {
        lock.lock();
        try {
            byMember.remove(memberToken);
        } finally {
            lock.unlock();
        }
    }

    /** Removes the request unless a newer one took its id. Called with the lock held. */
    private void forget(String memberToken, String reqId, Call call) {
        Map<String, Call> calls = byMember.get(memberToken);
        if (calls != null && calls.get(reqId) == call) {
            calls.remove(reqId);
        }
    }

    /** One request and, once answered, its answer. The mutable fields change only under the history's lock. */
    private static final class Call {
        private final String type;
        private final JsonNode data;
        private ReplyHandler waiter;
        private boolean done;
        private Map<String, Object> ok;
        private ApiException error;

        private Call(String type, JsonNode data, ReplyHandler waiter) {
            this.type = type;
            this.data = data;
            this.waiter = waiter;
        }

        private boolean sameAs(String otherType, JsonNode otherData) {
            return type.equals(otherType) && Objects.equals(data, otherData);
        }

        /** Answers a copy; only for a done call, whose answer no longer changes. */
        private void replay(ReplyHandler reply) {
            if (error != null) {
                reply.error(error);
            } else {
                reply.ok(ok);
            }
        }
    }

    /** The answer channel of a registered request. */
    final class Tracked implements ReplyHandler {
        private final String memberToken;
        private final String reqId;
        private final Call call;

        private Tracked(String memberToken, String reqId, Call call) {
            this.memberToken = memberToken;
            this.reqId = reqId;
            this.call = call;
        }

        @Override
        public void ok(Map<String, Object> data) {
            ReplyHandler to;
            lock.lock();
            try {
                if (call.done) {
                    return;
                }
                call.done = true;
                call.ok = data;
                to = call.waiter;
                call.waiter = null;
            } finally {
                lock.unlock();
            }
            to.ok(data);
        }

        @Override
        public void error(ApiException e) {
            ReplyHandler to;
            lock.lock();
            try {
                if (call.done) {
                    return;
                }
                call.done = true;
                to = call.waiter;
                call.waiter = null;
                if (e.code() == ErrorCode.RATE_LIMITED) {
                    // The client retries this refusal with the same reqId: the retry must run, not get it replayed.
                    forget(memberToken, reqId, call);
                } else {
                    call.error = e;
                }
            } finally {
                lock.unlock();
            }
            to.error(e);
        }

        /** Forgets the request so that a copy runs again: it ended without an answer. */
        void abandon() {
            lock.lock();
            try {
                forget(memberToken, reqId, call);
            } finally {
                lock.unlock();
            }
        }
    }
}
