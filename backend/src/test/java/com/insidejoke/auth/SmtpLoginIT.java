package com.insidejoke.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.insidejoke.support.AbstractIntegrationTest;
import com.insidejoke.support.ApiClient;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** MAIL_PROVIDER=smtp end to end: the app sends the code to a real SMTP server, and the code signs the host in. */
class SmtpLoginIT extends AbstractIntegrationTest {

    private static final GreenMail SMTP = new GreenMail(ServerSetupTest.SMTP.dynamicPort())
            .withConfiguration(GreenMailConfiguration.aConfig()
                    .withUser("login@insidejoke.test", "login@insidejoke.test", "app-pass"));

    static {
        SMTP.start();
    }

    @DynamicPropertySource
    static void smtp(DynamicPropertyRegistry registry) {
        registry.add("app.mail.provider", () -> "smtp");
        registry.add("app.mail.from", () -> "Inside Joke <login@insidejoke.test>");
        registry.add("app.mail.smtp.host", () -> "localhost");
        registry.add("app.mail.smtp.port", () -> SMTP.getSmtp().getPort());
        registry.add("app.mail.smtp.username", () -> "login@insidejoke.test");
        registry.add("app.mail.smtp.password", () -> "app-pass");
        registry.add("app.mail.smtp.security", () -> "none");
    }

    @AfterAll
    static void stopServer() {
        SMTP.stop();
    }

    private static MimeMessage mailTo(String email) throws Exception {
        for (int i = 0; i < 50; i++) {
            for (MimeMessage m : SMTP.getReceivedMessages()) {
                if (Arrays.stream(m.getRecipients(Message.RecipientType.TO))
                        .anyMatch(a -> a.toString().equals(email))) {
                    return m;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("No email reached " + email);
    }

    @Test
    void theCodeArrivesOverSmtpAndSignsTheHostIn() throws Exception {
        String email = uniqueEmail("smtp");
        ApiClient.Resp sent = client().post("/api/auth/magic-link", Map.of("email", email, "language", "ru"));
        assertThat(sent.status() / 100).as(sent.body()).isEqualTo(2);

        MimeMessage mail = mailTo(email);
        assertThat(mail.getFrom()[0].toString()).contains("login@insidejoke.test");
        Matcher code = Pattern.compile("Ваш код Inside Joke: (\\d{3}) (\\d{3})").matcher(mail.getSubject());
        assertThat(code.matches()).as(mail.getSubject()).isTrue();

        ApiClient host = client();
        assertThat(host.post(
                                "/api/auth/email-code/verify",
                                Map.of("email", email, "code", code.group(1) + code.group(2)))
                        .status())
                .isEqualTo(200);
        assertThat(host.get("/api/me").json().path("email").asString()).isEqualTo(email);
    }
}
