package com.insidejoke.admin;

import com.insidejoke.admin.dto.CurrencyRevenueDto;
import com.insidejoke.admin.dto.NotAppliedEventDto;
import com.insidejoke.admin.dto.PageDto;
import com.insidejoke.admin.dto.PassLogEntryDto;
import com.insidejoke.admin.dto.PassSummaryDto;
import com.insidejoke.admin.dto.PassTypeStatsDto;
import com.insidejoke.billing.PassLogRepository;
import com.insidejoke.billing.PassLogStatus;
import com.insidejoke.billing.PassSource;
import com.insidejoke.billing.Product;
import com.insidejoke.billing.WebhookEventRepository;
import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The admin pass log: every pass, bought or granted, with who, when, how much and what happened to it; a summary for
 * the same filters; and the refunds and chargebacks the server received but did not apply. Queries live in
 * {@link PassLogRepository} and {@link WebhookEventRepository}; this class checks the parameters and shapes the answer.
 */
@Service
public class PassReportService {

    public static final int MAX_PAGE_SIZE = 100;

    private final PassLogRepository passLog;
    private final WebhookEventRepository webhookEvents;
    private final Clock clock;

    public PassReportService(PassLogRepository passLog, WebhookEventRepository webhookEvents, Clock clock) {
        this.passLog = passLog;
        this.webhookEvents = webhookEvents;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ validation

    public static PassLogRepository.Filter filter(
            String type, String source, String status, LocalDate from, LocalDate to, String email) {
        Map<String, String> errors = new LinkedHashMap<>();
        Product t = parse(Product.class, type);
        PassSource src = parse(PassSource.class, source);
        PassLogStatus st = parse(PassLogStatus.class, status);
        if (type != null && !type.isBlank() && t == null) {
            errors.put("type", "one of " + names(Product.class));
        }
        if (source != null && !source.isBlank() && src == null) {
            errors.put("source", "one of " + names(PassSource.class));
        }
        if (status != null && !status.isBlank() && st == null) {
            errors.put("status", "one of " + names(PassLogStatus.class));
        }
        if (from != null && to != null && from.isAfter(to)) {
            errors.put("from", "must be on or before 'to'");
        }
        String e = email == null || email.isBlank() ? null : email.trim();
        if (e != null && e.length() > 254) {
            errors.put("email", "at most 254 characters");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Check the filters.", Map.of("fields", errors));
        }
        return new PassLogRepository.Filter(t, src, st, from, to, e);
    }

    static void checkPage(int page, int size) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (page < 0) {
            errors.put("page", "0 or more");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            errors.put("size", "1 to " + MAX_PAGE_SIZE);
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Check the page parameters.", Map.of("fields", errors));
        }
    }

    /** The constant named {@code value} (any case), or null when the value is empty or unknown. */
    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(value.trim())) {
                return constant;
            }
        }
        return null;
    }

    private static <E extends Enum<E>> String names(Class<E> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).sorted().collect(Collectors.joining(", "));
    }

    private static String upper(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    private static PassLogRepository.Sort sort(String sort) {
        String key = sort == null ? "created" : sort.trim().toLowerCase(Locale.ROOT);
        for (PassLogRepository.Sort s : PassLogRepository.Sort.values()) {
            if (s.name().toLowerCase(Locale.ROOT).equals(key)) {
                return s;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ pass log

    public PageDto<PassLogEntryDto> passes(PassLogRepository.Filter f, String sort, String dir, int page, int size) {
        checkPage(page, size);
        PassLogRepository.Sort column = sort(sort);
        Boolean ascending = dir == null || dir.equalsIgnoreCase("desc")
                ? Boolean.FALSE
                : dir.equalsIgnoreCase("asc") ? Boolean.TRUE : null;
        if (column == null || ascending == null) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Unknown sort.",
                    Map.of("fields", Map.of("sort", "one of amount, created, email, ends; dir asc or desc")));
        }
        Instant now = clock.instant();
        long total = passLog.count(f, now);
        List<PassLogEntryDto> items = passLog.list(f, column, ascending, size, (long) page * size, now).stream()
                .map(e -> new PassLogEntryDto(
                        e.id(),
                        e.type(),
                        e.source(),
                        e.status(),
                        e.userId(),
                        e.userEmail(),
                        e.userDeleted(),
                        e.createdAt(),
                        e.startsAt(),
                        e.endsAt(),
                        e.monthlyGameLimit(),
                        e.gamesPlayed(),
                        e.txnId(),
                        e.amountMinor(),
                        e.currency(),
                        e.refundedAt(),
                        e.refundKind(),
                        e.grantedByEmail(),
                        e.revokedAt(),
                        e.revokeReason(),
                        e.revokedByEmail()))
                .toList();
        return PageDto.of(items, page, size, total);
    }

    public PassSummaryDto summary(PassLogRepository.Filter f) {
        Instant now = clock.instant();
        Map<String, PassTypeStatsDto> byType = new LinkedHashMap<>();
        long sold = 0;
        long granted = 0;
        long refunded = 0;
        long chargebacks = 0;
        long active = 0;
        for (PassLogRepository.TypeCounts c : passLog.countsByType(f, now)) {
            byType.put(c.type(), new PassTypeStatsDto(c.sold(), c.granted(), c.refunded(), c.chargebacks()));
            sold += c.sold();
            granted += c.granted();
            refunded += c.refunded();
            chargebacks += c.chargebacks();
            active += c.active();
        }
        List<CurrencyRevenueDto> revenue = passLog.revenue(f, now).stream()
                .map(r -> new CurrencyRevenueDto(
                        r.gross().currency(),
                        r.gross().minor(),
                        r.refunded().minor(),
                        r.gross().minus(r.refunded()).minor()))
                .toList();
        double rate = sold == 0 ? 0 : Math.round((refunded + chargebacks) * 1000.0 / sold) / 1000.0;
        return new PassSummaryDto(sold, granted, refunded, chargebacks, rate, active, revenue, byType);
    }

    // ------------------------------------------------------------------ refunds and chargebacks not applied

    public PageDto<NotAppliedEventDto> notApplied(boolean onlyStillHeld, int page, int size) {
        checkPage(page, size);
        Instant now = clock.instant();
        long total = webhookEvents.countNotApplied(onlyStillHeld, now);
        List<NotAppliedEventDto> items = webhookEvents.notApplied(onlyStillHeld, size, (long) page * size, now).stream()
                .map(e -> new NotAppliedEventDto(
                        e.eventId(),
                        e.eventType(),
                        e.receivedAt(),
                        e.action(),
                        e.status(),
                        e.txnId(),
                        e.reason(),
                        e.detail(),
                        e.purchaseId(),
                        e.product(),
                        e.amountMinor(),
                        e.currency(),
                        e.purchaseStatus(),
                        e.userId(),
                        e.userEmail(),
                        e.passId(),
                        e.passRevokedAt(),
                        e.passEndsAt(),
                        e.passId() != null
                                && e.passRevokedAt() == null
                                && e.passEndsAt() != null
                                && e.passEndsAt().isAfter(now)))
                .toList();
        return PageDto.of(items, page, size, total);
    }
}
