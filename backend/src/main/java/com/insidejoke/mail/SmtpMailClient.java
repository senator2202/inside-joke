package com.insidejoke.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.util.Locale;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Sends sign-in emails through an SMTP server (MAIL_PROVIDER=smtp): Gmail, Yandex, a company server. */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "smtp")
public class SmtpMailClient implements MailClient {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailClient.class);

    private final MailProperties props;
    private final JavaMailSenderImpl mailer;

    public SmtpMailClient(MailProperties props) {
        this.props = props;
        this.mailer = mailer(props.smtp());
        MailProperties.Smtp smtp = props.smtp();
        if (smtp.configured()) {
            log.info(
                    "Sign-in emails go through SMTP {}:{} ({}{})",
                    smtp.host(),
                    smtp.port(),
                    smtp.security(),
                    smtp.authenticates() ? ", as " + smtp.username() : ", no login");
        } else {
            log.warn("MAIL_PROVIDER=SMTP but SMTP_HOST is not set: sign-in emails can't be sent");
        }
    }

    /** A mailer for these settings; package-private so tests can check the session properties. */
    static JavaMailSenderImpl mailer(MailProperties.Smtp smtp) {
        JavaMailSenderImpl mailer = new JavaMailSenderImpl();
        mailer.setHost(smtp.host());
        mailer.setPort(smtp.port());
        mailer.setDefaultEncoding("UTF-8");
        if (smtp.authenticates()) {
            mailer.setUsername(smtp.username());
            mailer.setPassword(password(smtp));
        }
        Properties p = mailer.getJavaMailProperties();
        p.put("mail.smtp.auth", String.valueOf(smtp.authenticates()));
        String millis = String.valueOf(smtp.timeout().toMillis());
        p.put("mail.smtp.connectiontimeout", millis);
        p.put("mail.smtp.timeout", millis);
        p.put("mail.smtp.writetimeout", millis);
        switch (smtp.security()) {
            case STARTTLS -> {
                p.put("mail.smtp.starttls.enable", "true");
                p.put("mail.smtp.starttls.required", "true");
                p.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case SSL -> {
                p.put("mail.smtp.ssl.enable", "true");
                p.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case NONE -> {
                // plain text: a local relay or a test server
            }
        }
        return mailer;
    }

    /** Google shows app passwords as four groups of four letters with spaces between; the spaces are not part of them. */
    static String password(MailProperties.Smtp smtp) {
        String host = smtp.host().toLowerCase(Locale.ROOT);
        boolean google = host.endsWith("gmail.com") || host.endsWith("googlemail.com");
        return google ? smtp.password().replaceAll("\\s+", "") : smtp.password();
    }

    @Override
    public void send(EmailMessage message) {
        if (!props.smtp().configured()) {
            throw new MailSendException("SMTP_HOST is not configured", null);
        }
        MimeMessage mime = mailer.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(sender(props.from()));
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            helper.setText(message.text(), message.html());
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailSendException("Could not build the email: " + describe(e), e);
        }
        try {
            mailer.send(mime);
        } catch (MailAuthenticationException e) {
            throw new MailSendException("SMTP login failed: " + describe(e), e);
        } catch (MailException e) {
            throw new MailSendException("SMTP sending failed: " + describe(e), e);
        }
    }

    /** Parses {@code Name <address>}; the display name is encoded as UTF-8, so non-Latin names survive. */
    static InternetAddress sender(String from) throws MessagingException, UnsupportedEncodingException {
        InternetAddress parsed = new InternetAddress(from, true);
        return parsed.getPersonal() == null
                ? parsed
                : new InternetAddress(parsed.getAddress(), parsed.getPersonal(), "UTF-8");
    }

    /** The server's own words, from the innermost cause that has any, on one line and not too long. */
    static String describe(Throwable e) {
        String first = null;
        String deepest = null;
        Throwable t = e;
        do {
            if (t.getMessage() != null && !t.getMessage().isBlank()) {
                String m = t.getMessage().replaceAll("\\s+", " ").trim();
                if (first == null) {
                    first = m;
                }
                deepest = m;
            }
            t = t.getCause();
        } while (t != null);
        String text = first == null
                ? e.getClass().getSimpleName()
                : deepest.equals(first) || first.contains(deepest) ? first : first + " (" + deepest + ")";
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }
}
