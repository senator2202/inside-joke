package com.insidejoke.mail;

import java.io.Serial;

/** Sends one transactional email. Throws {@link MailSendException} when delivery could not be requested. */
public interface MailClient {

    void send(EmailMessage message);

    final class MailSendException extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 1L;

        public MailSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
