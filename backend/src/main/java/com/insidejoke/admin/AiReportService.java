package com.insidejoke.admin;

import com.insidejoke.admin.dto.AiCallDto;
import com.insidejoke.admin.dto.AiCallSummaryDto;
import com.insidejoke.admin.dto.AiStatusDto;
import com.insidejoke.admin.dto.PageDto;
import com.insidejoke.admin.dto.RateLimitWindowDto;
import com.insidejoke.admin.dto.RateLimitsDto;
import com.insidejoke.ai.AiCallRepository;
import com.insidejoke.ai.AiOutcome;
import com.insidejoke.ai.AiPurpose;
import com.insidejoke.ai.AnthropicClient;
import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/** The admin AI tab: whether the Anthropic key works right now, and the log of every AI call. */
@Service
public class AiReportService {

    /** Outcome filter value meaning "any failure": the provider was called and it didn't work. */
    public static final String FAILURES = "FAILURES";

    private final AiCallRepository calls;
    private final AnthropicClient anthropic;

    public AiReportService(AiCallRepository calls, AnthropicClient anthropic) {
        this.calls = calls;
        this.anthropic = anthropic;
    }

    // ------------------------------------------------------------------ status

    public AiStatusDto status() {
        return new AiStatusDto(
                anthropic.enabled(),
                anthropic.model(),
                calls.latestAnthropic(false).map(AiReportService::summary).orElse(null),
                calls.latestAnthropic(true).map(AiReportService::summary).orElse(null),
                anthropic.rateLimits().map(AiReportService::view).orElse(null));
    }

    private static AiCallSummaryDto summary(AiCallRepository.LoggedCall c) {
        return new AiCallSummaryDto(c.createdAt(), c.purpose(), c.model(), c.outcome(), c.latencyMs(), c.error());
    }

    static RateLimitsDto view(AnthropicClient.RateLimits limits) {
        Map<String, String> h = limits.headers();
        return new RateLimitsDto(
                limits.capturedAt(),
                limits.httpStatus(),
                window(h, "requests"),
                window(h, "tokens"),
                window(h, "input-tokens"),
                window(h, "output-tokens"),
                h.get("retry-after"));
    }

    private static RateLimitWindowDto window(Map<String, String> headers, String kind) {
        String prefix = "anthropic-ratelimit-" + kind + "-";
        Long limit = number(headers.get(prefix + "limit"));
        Long remaining = number(headers.get(prefix + "remaining"));
        String reset = headers.get(prefix + "reset");
        return limit == null && remaining == null && reset == null
                ? null
                : new RateLimitWindowDto(limit, remaining, reset);
    }

    private static Long number(String value) {
        try {
            return value == null ? null : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ call log

    public PageDto<AiCallDto> calls(LocalDate from, LocalDate to, String purpose, String outcome, int page, int size) {
        PassReportService.checkPage(page, size);
        Map<String, String> errors = new LinkedHashMap<>();
        String p = purpose == null || purpose.isBlank() ? null : purpose.trim().toUpperCase(Locale.ROOT);
        String o = outcome == null || outcome.isBlank() ? null : outcome.trim().toUpperCase(Locale.ROOT);
        List<String> purposes =
                Arrays.stream(AiPurpose.values()).map(Enum::name).toList();
        List<String> outcomes = new ArrayList<>(
                Arrays.stream(AiOutcome.values()).map(Enum::name).toList());
        outcomes.add(FAILURES);
        if (p != null && !purposes.contains(p)) {
            errors.put("purpose", "one of " + String.join(", ", purposes));
        }
        if (o != null && !outcomes.contains(o)) {
            errors.put("outcome", "one of " + String.join(", ", outcomes));
        }
        if (from != null && to != null && from.isAfter(to)) {
            errors.put("from", "must be on or before 'to'");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Check the filters.", Map.of("fields", errors));
        }

        AiCallRepository.Search search = new AiCallRepository.Search(
                from == null ? null : from.atStartOfDay(ZoneOffset.UTC).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(),
                p,
                FAILURES.equals(o) ? null : o,
                FAILURES.equals(o));
        long total = calls.count(search);
        List<AiCallDto> items = calls.search(search, size, (long) page * size).stream()
                .map(c -> new AiCallDto(
                        c.id(),
                        c.createdAt(),
                        c.purpose(),
                        c.provider(),
                        c.model(),
                        c.promptVersion(),
                        c.outcome(),
                        c.inputTokens(),
                        c.outputTokens(),
                        c.ttsChars(),
                        c.costMicros(),
                        c.latencyMs(),
                        c.freeGame(),
                        c.gameSessionId(),
                        c.roomCode(),
                        c.error()))
                .toList();
        return PageDto.of(items, page, size, total);
    }
}
