package com.insidejoke.room;

import com.insidejoke.analytics.AnalyticsService;
import com.insidejoke.auth.AppPrincipal;
import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import com.insidejoke.game.FeedbackRepository;
import com.insidejoke.game.GameSessionRepository;
import com.insidejoke.room.dto.FeedbackRequestDto;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The host's 1-5 star rating after a game (S9). One per game; sending again replaces it. */
@RestController
public class FeedbackController {

    private final GameSessionRepository sessions;
    private final FeedbackRepository feedback;
    private final AnalyticsService analytics;
    private final Clock clock;

    public FeedbackController(
            GameSessionRepository sessions, FeedbackRepository feedback, AnalyticsService analytics, Clock clock) {
        this.sessions = sessions;
        this.feedback = feedback;
        this.analytics = analytics;
        this.clock = clock;
    }

    @PostMapping("/api/games/{gameId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submit(
            @AuthenticationPrincipal AppPrincipal user,
            @PathVariable UUID gameId,
            @RequestBody FeedbackRequestDto body) {
        UUID host = sessions.findHost(gameId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such game."));
        if (!host.equals(user.id())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Only the host can rate this game.");
        }
        if (body.rating() == null || body.rating() < 1 || body.rating() > 5) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Rating must be 1 to 5.", Map.of("fields", Map.of("rating", "1-5")));
        }
        String comment = body.comment() == null || body.comment().isBlank()
                ? null
                : body.comment().trim();
        if (comment != null && comment.length() > 500) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Keep the comment under 500 characters.",
                    Map.of("fields", Map.of("comment", "max 500")));
        }
        feedback.upsert(gameId, body.rating(), comment, clock.instant());
        analytics.track("feedback_submitted", user.id().toString(), Map.of("source", "host", "rating", body.rating()));
    }
}
