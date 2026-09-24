package com.insidejoke.support;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resend API on the fake server plus helpers to read the code and link from the captured email. */
public final class FakeResend {

    private static final Pattern CODE = Pattern.compile("sign-in code is (\\d{3}) (\\d{3})");
    private static final Pattern LINK = Pattern.compile("/auth/callback\\?token=([A-Za-z0-9_-]+)");

    private FakeResend() {}

    public static void accept(FakeHttp fake) {
        fake.on("POST", "/resend/emails", r -> FakeHttp.Reply.json(200, "{\"id\":\"email_1\"}"));
    }

    public static void fail(FakeHttp fake) {
        fake.on("POST", "/resend/emails", r -> FakeHttp.Reply.json(500, "{\"message\":\"down\"}"));
    }

    public static String lastCode(FakeHttp fake) {
        Matcher m = CODE.matcher(lastBody(fake));
        if (!m.find()) {
            throw new AssertionError("No code in email");
        }
        return m.group(1) + m.group(2);
    }

    public static String lastLinkToken(FakeHttp fake) {
        Matcher m = LINK.matcher(lastBody(fake));
        if (!m.find()) {
            throw new AssertionError("No link in email");
        }
        return m.group(1);
    }

    private static String lastBody(FakeHttp fake) {
        List<FakeHttp.Recorded> sent = fake.requests("POST", "/resend/emails");
        if (sent.isEmpty()) {
            throw new AssertionError("No email sent");
        }
        return sent.getLast().body();
    }
}
