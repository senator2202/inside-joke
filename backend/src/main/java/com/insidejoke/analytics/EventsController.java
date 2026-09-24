package com.insidejoke.analytics;

import com.insidejoke.analytics.dto.ClientEventRequestDto;
import com.insidejoke.auth.AppPrincipal;
import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import com.insidejoke.web.ClientIpUtils;
import com.insidejoke.web.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Browser-side events, relayed to PostHog by the server so the project key stays private and ad blockers
 * don't skew the funnel. Only a fixed list of events with short scalar properties is accepted.
 */
@RestController
public class EventsController {

    static final Set<String> ALLOWED = Set.of("landing_viewed", "checkout_opened", "guest_host_cta_clicked");
    private static final Pattern ANON_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final AnalyticsService analytics;
    private final RateLimitService limiter;

    public EventsController(AnalyticsService analytics, RateLimitService limiter) {
        this.analytics = analytics;
        this.limiter = limiter;
    }

    @PostMapping("/api/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void track(
            @AuthenticationPrincipal AppPrincipal user,
            @RequestBody ClientEventRequestDto body,
            HttpServletRequest request) {
        limiter.check(RateLimitService.EVENTS_PER_IP, ClientIpUtils.of(request));
        if (body.event() == null || !ALLOWED.contains(body.event())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown event.");
        }
        Map<String, Object> props = new HashMap<>();
        if (body.properties() != null) {
            body.properties().forEach((k, v) -> {
                if (props.size() < 10
                        && k.length() <= 40
                        && (v instanceof Number
                                || v instanceof Boolean
                                || v instanceof String s && s.length() <= 100)) {
                    props.put(k, v);
                }
            });
        }
        String distinct = user != null
                ? user.id().toString()
                : body.anonymousId() != null
                                && ANON_ID.matcher(body.anonymousId()).matches()
                        ? "anon:" + body.anonymousId()
                        : null;
        analytics.track(body.event(), distinct, props);
    }
}
