package com.insidejoke.mail;

public record EmailMessage(String to, String subject, String text, String html) {}
