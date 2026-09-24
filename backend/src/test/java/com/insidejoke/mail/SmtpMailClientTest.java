package com.insidejoke.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SmtpMailClientTest {

    private GreenMail server;

    @BeforeEach
    void start() {
        server = new GreenMail(ServerSetupTest.SMTP.dynamicPort())
                .withConfiguration(
                        GreenMailConfiguration.aConfig().withUser("bot@example.com", "bot@example.com", "s3cret"));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private MailProperties.Smtp smtp(String password) {
        return new MailProperties.Smtp(
                "localhost",
                server.getSmtp().getPort(),
                "bot@example.com",
                password,
                MailProperties.Security.NONE,
                Duration.ofSeconds(5));
    }

    private SmtpMailClient sender(MailProperties.Smtp smtp) {
        return new SmtpMailClient(new MailProperties("smtp", "Inside Joke <bot@example.com>", null, smtp));
    }

    private static final EmailMessage RUSSIAN = new EmailMessage(
            "masha@example.com",
            "Ваш код Inside Joke: 123 456",
            "Ваш код для входа в Inside Joke: 123 456",
            "<p>Ваш код для входа в Inside Joke:</p><p>123 456</p>");

    @Test
    void deliversATextAndHtmlEmailInUtf8() throws Exception {
        sender(smtp("s3cret")).send(RUSSIAN);

        assertThat(server.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage received = server.getReceivedMessages()[0];
        assertThat(received.getSubject()).isEqualTo("Ваш код Inside Joke: 123 456");
        InternetAddress from = (InternetAddress) received.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("bot@example.com");
        assertThat(from.getPersonal()).isEqualTo("Inside Joke");
        assertThat(received.getAllRecipients()[0].toString()).isEqualTo("masha@example.com");
        String body = GreenMailUtil.getBody(received);
        assertThat(received.getContent()).isInstanceOf(MimeMultipart.class);
        assertThat(body).contains("text/plain").contains("text/html");
        assertThat(GreenMailUtil.getWholeMessage(received)).contains("UTF-8");
    }

    @Test
    void aWrongPasswordSaysTheLoginFailed() {
        assertThatThrownBy(() -> sender(smtp("wrong")).send(RUSSIAN))
                .isInstanceOf(MailClient.MailSendException.class)
                .hasMessageStartingWith("SMTP login failed: ");
        assertThat(server.getReceivedMessages()).isEmpty();
    }

    @Test
    void anUnreachableServerSaysSo() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        MailProperties.Smtp nowhere = new MailProperties.Smtp(
                "localhost", closedPort, "", "", MailProperties.Security.NONE, Duration.ofSeconds(2));
        assertThatThrownBy(() -> sender(nowhere).send(RUSSIAN))
                .isInstanceOf(MailClient.MailSendException.class)
                .hasMessageStartingWith("SMTP sending failed: ")
                .hasMessageContaining(String.valueOf(closedPort));
    }

    @Test
    void withoutAHostNothingIsAttempted() {
        MailProperties.Smtp none = new MailProperties.Smtp(null, null, null, null, null, null);
        assertThat(none.port()).isEqualTo(587);
        assertThat(none.security()).isEqualTo(MailProperties.Security.STARTTLS);
        assertThatThrownBy(() -> sender(none).send(RUSSIAN)).hasMessage("SMTP_HOST is not configured");
    }

    @Test
    void eachSecurityModeSetsUpTheConnection() {
        Properties starttls = SmtpMailClient.mailer(new MailProperties.Smtp(
                        "smtp.gmail.com", null, "me@gmail.com", "x", MailProperties.Security.STARTTLS, null))
                .getJavaMailProperties();
        assertThat(starttls)
                .containsEntry("mail.smtp.starttls.enable", "true")
                .containsEntry("mail.smtp.starttls.required", "true")
                .containsEntry("mail.smtp.ssl.checkserveridentity", "true")
                .containsEntry("mail.smtp.auth", "true")
                .containsEntry("mail.smtp.timeout", "10000")
                .doesNotContainKey("mail.smtp.ssl.enable");

        MailProperties.Smtp ssl =
                new MailProperties.Smtp("smtp.gmail.com", null, "me@gmail.com", "x", MailProperties.Security.SSL, null);
        assertThat(ssl.port()).isEqualTo(465);
        assertThat(SmtpMailClient.mailer(ssl).getJavaMailProperties())
                .containsEntry("mail.smtp.ssl.enable", "true")
                .doesNotContainKey("mail.smtp.starttls.enable");

        Properties plain = SmtpMailClient.mailer(
                        new MailProperties.Smtp("relay.local", 25, "", "", MailProperties.Security.NONE, null))
                .getJavaMailProperties();
        assertThat(plain)
                .containsEntry("mail.smtp.auth", "false")
                .doesNotContainKey("mail.smtp.starttls.enable")
                .doesNotContainKey("mail.smtp.ssl.enable");
    }

    @Test
    void gmailAppPasswordsLoseTheSpacesGoogleShowsThemWith() {
        assertThat(SmtpMailClient.password(new MailProperties.Smtp(
                        "smtp.gmail.com", null, "me@gmail.com", "abcd efgh ijkl mnop", null, null)))
                .isEqualTo("abcdefghijklmnop");
        assertThat(SmtpMailClient.password(
                        new MailProperties.Smtp("smtp.example.com", null, "me", "pass with spaces", null, null)))
                .as("other servers get the password exactly as written")
                .isEqualTo("pass with spaces");
    }

    @Test
    void anUnknownProviderStopsTheServerWithAClearMessage() {
        assertThatThrownBy(() -> new MailProperties("sendmail", null, null, null))
                .hasMessage("MAIL_PROVIDER must be log, resend or smtp, not 'sendmail'");
        assertThat(new MailProperties(" SMTP ", null, null, null).provider()).isEqualTo("smtp");
    }
}
