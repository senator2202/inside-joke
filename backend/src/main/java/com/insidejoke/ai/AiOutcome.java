package com.insidejoke.ai;

import java.util.EnumSet;
import java.util.Set;

/** What came of one AI call; stored in {@code ai_call.outcome}. */
public enum AiOutcome {
    OK,
    TIMEOUT,
    ERROR,
    INVALID_JSON,
    /** The provider was not called (budget or outage) and prewritten content was used instead. */
    FALLBACK;

    /** The provider was called and it didn't work. */
    public static final Set<AiOutcome> FAILURES = EnumSet.of(ERROR, TIMEOUT, INVALID_JSON);
}
