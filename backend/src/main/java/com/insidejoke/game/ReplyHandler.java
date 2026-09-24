package com.insidejoke.game;

import com.insidejoke.common.ApiException;
import java.util.Map;

/** Answer to one client request; may be completed later, after an AI check. */
public interface ReplyHandler {

    ReplyHandler NONE = new ReplyHandler() {
        @Override
        public void ok(Map<String, Object> data) {}

        @Override
        public void error(ApiException e) {}
    };

    void ok(Map<String, Object> data);

    void error(ApiException e);

    default void ok() {
        ok(Map.of());
    }
}
