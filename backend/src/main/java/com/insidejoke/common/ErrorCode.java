package com.insidejoke.common;

import org.springframework.http.HttpStatus;

/** Machine-readable error codes shared by REST and WebSocket responses. */
public enum ErrorCode {
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Sign in to continue."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "You are not allowed to do that."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Not found."),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Some fields are invalid."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again later."),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "Something broke on our side."),

    EMAIL_SEND_FAILED(HttpStatus.BAD_GATEWAY, "We couldn't send the email."),
    CODE_INVALID(HttpStatus.BAD_REQUEST, "That code doesn't match."),
    CODE_EXPIRED(HttpStatus.BAD_REQUEST, "That code has expired."),
    CODE_LOCKED(HttpStatus.BAD_REQUEST, "Too many wrong codes. Request a new one."),
    LINK_INVALID(HttpStatus.BAD_REQUEST, "This sign-in link no longer works."),
    GOOGLE_DISABLED(HttpStatus.NOT_FOUND, "Google sign-in is not configured."),

    ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "Room not found."),
    ROOM_FULL(HttpStatus.CONFLICT, "The room already has 8 players."),
    ROOM_LOCKED(HttpStatus.CONFLICT, "The host closed entry to this room."),
    ROOM_IN_PROGRESS(HttpStatus.CONFLICT, "The game is already running."),
    NAME_TAKEN(HttpStatus.CONFLICT, "That name is already taken."),
    NAME_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Pick a different name."),
    NOT_STREAMER_MODE(HttpStatus.CONFLICT, "This room is not open to an audience."),
    AUDIENCE_FULL(HttpStatus.CONFLICT, "The audience is full."),
    DRAIN_MODE(HttpStatus.SERVICE_UNAVAILABLE, "Creating rooms is paused for a short update."),

    PAYWALL_FREE_LIMIT(HttpStatus.PAYMENT_REQUIRED, "This week's free game has been played."),
    PAYWALL_MONTHLY_LIMIT(HttpStatus.PAYMENT_REQUIRED, "This month's Host Pass games are used up."),
    BUDGET_PAUSED(HttpStatus.PAYMENT_REQUIRED, "Free games are paused because of high demand."),
    PAYMENTS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Payments are temporarily unavailable."),

    NOT_ALLOWED(HttpStatus.FORBIDDEN, "Your role can't do that."),
    INVALID_PHASE(HttpStatus.CONFLICT, "That's not possible right now."),
    NOT_ENOUGH_PLAYERS(HttpStatus.CONFLICT, "At least 3 players are needed."),
    MODERATION_BLOCKED(HttpStatus.UNPROCESSABLE_CONTENT, "The host won't use that."),
    MODERATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Couldn't check that right now."),
    DOSSIER_LIMIT(HttpStatus.CONFLICT, "Secret limit reached for this game."),
    BAD_MESSAGE(HttpStatus.BAD_REQUEST, "Malformed message.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
