package com.insidejoke.auth.dto;

/** The sign-in code was emailed; a new one can be asked for after this many seconds. */
public record CodeSentDto(boolean sent, int resendAfterSeconds) {}
