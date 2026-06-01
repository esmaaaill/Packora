package com.packora.backend.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Autowired
    private JavaMailSender mailSender;

    /**
     * The Gmail address used as the "From" header.
     * Defaults to an empty string so the app still starts if MAIL_USERNAME
     * isn't set — the SMTP send will fail at runtime with a clear error.
     */
    @Value("${spring.mail.username:}")
    private String fromEmail;

    /**
     * Sends a password-reset email containing a clickable HTML link.
     *
     * Uses MimeMessage (HTML) instead of SimpleMailMessage (plain text) to
     * avoid the jakarta.mail / javax.mail mismatch that can silently break
     * SimpleMailMessage in Spring Boot 4.
     *
     * @param toEmail     recipient email address
     * @param token       the UUID reset token
     * @param frontendUrl base URL of the frontend (e.g. http://localhost:3000)
     * @throws MessagingException if the SMTP transport fails
     */
    public void sendPasswordResetEmail(String toEmail, String token, String frontendUrl)
            throws MessagingException {

        String resetLink = frontendUrl + "/reset-password?token=" + token;

        log.info("[EmailService] Sending password reset email to {} via SMTP", toEmail);

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");

        helper.setFrom(fromEmail);
        helper.setTo(toEmail);
        helper.setSubject("Packora – Password Reset Request");

        // Plain-text body
        String text =
                "Hello,\n\n"
                + "We received a request to reset your Packora account password.\n\n"
                + "Click the link below to choose a new password (valid for 30 minutes):\n"
                + resetLink + "\n\n"
                + "If you did not request a password reset, please ignore this email "
                + "— your password will remain unchanged.\n\n"
                + "Best regards,\n"
                + "The Packora Team";

        helper.setText(text, false);

        mailSender.send(message);
        log.info("[EmailService] Password reset email delivered successfully to {}", toEmail);
    }
}
