package com.insidejoke.admin.dto;

import java.util.List;

/** One page of a server-side paginated list. {@code page} is zero-based. */
public record PageDto<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static <T> PageDto<T> of(List<T> items, int page, int size, long totalItems) {
        return new PageDto<>(items, page, size, totalItems, (int) ((totalItems + size - 1) / size));
    }
}
