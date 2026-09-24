package com.insidejoke.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import java.util.Map;

/** Wire format of every error response. */
public record ErrorDto(ErrorDetailDto error) {

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public static ErrorDto of(ErrorCode code, String message, Map<String, Object> details) {
        return new ErrorDto(new ErrorDetailDto(code.name(), message, details));
    }

    public static ErrorDto of(ApiException e) {
        return of(e.code(), e.getMessage(), e.details());
    }
}
