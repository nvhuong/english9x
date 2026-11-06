package com.english9x.identity.internal;

import jakarta.mail.internet.MimeMessage;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends over SMTP (Mailpit in E2E) on a small bounded executor, so the HTTP answer never waits for the mail server
 * (which also keeps response timing the same whether or not a mail is sent) and a send failure cannot touch the
 * database transaction. Links are built from the configured public base URL only, never from the request's Host.
 * Text is Vietnamese by default, English for users whose locale is "en".
 */
@Component
class SmtpAuthMailer implements AuthMailer, DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(SmtpAuthMailer.class);

    private final JavaMailSender sender;
    private final String from;
    private final String baseUrl;
    private final ExecutorService executor = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(500), r -> {
                Thread t = new Thread(r, "e9x-auth-mail");
                t.setDaemon(true);
                return t;
            });

    SmtpAuthMailer(JavaMailSender sender, AuthProperties props, @Value("${e9x.public-base-url}") String baseUrl) {
        this.sender = sender;
        this.from = props.mailFrom();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    @Override
    public void sendVerification(String to, String displayName, String locale, String token) {
        String link = baseUrl + "/verify-email?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        if (en(locale)) {
            enqueue("verification", to, "Verify your English9x email",
                    greeting(displayName, true) + "Thanks for signing up. Confirm your email address with this link (valid for 24 hours):\n\n"
                            + link + "\n\nIf you did not create an English9x account you can ignore this message.\n");
        } else {
            enqueue("verification", to, "Xác thực email tài khoản English9x",
                    greeting(displayName, false) + "Cảm ơn bạn đã đăng ký. Hãy xác thực địa chỉ email bằng liên kết sau (có hiệu lực 24 giờ):\n\n"
                            + link + "\n\nNếu bạn không tạo tài khoản English9x, hãy bỏ qua email này.\n");
        }
    }

    @Override
    public void sendAlreadyRegistered(String to, String displayName, String locale) {
        String forgot = baseUrl + "/forgot-password";
        if (en(locale)) {
            enqueue("already-registered", to, "Someone tried to sign up with your email",
                    greeting(displayName, true) + "Someone tried to create an English9x account with this email address, which already has one. "
                            + "Nothing has changed. If it was you and you forgot your password, you can reset it here:\n\n" + forgot
                            + "\n\nIf it was not you, no action is needed.\n");
        } else {
            enqueue("already-registered", to, "Có người vừa đăng ký bằng email của bạn",
                    greeting(displayName, false) + "Có người vừa thử tạo tài khoản English9x bằng địa chỉ email này, nhưng email đã có tài khoản. "
                            + "Không có gì thay đổi. Nếu là bạn và bạn quên mật khẩu, hãy đặt lại tại đây:\n\n" + forgot
                            + "\n\nNếu không phải bạn, bạn không cần làm gì.\n");
        }
    }

    @Override
    public void sendPasswordReset(String to, String displayName, String locale, String token) {
        String link = baseUrl + "/reset-password?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        if (en(locale)) {
            enqueue("reset", to, "Reset your English9x password",
                    greeting(displayName, true) + "Use this link to choose a new password (valid for 1 hour, one use):\n\n" + link
                            + "\n\nIf you did not ask for this, ignore this message: your password has not changed.\n");
        } else {
            enqueue("reset", to, "Đặt lại mật khẩu English9x",
                    greeting(displayName, false) + "Dùng liên kết sau để chọn mật khẩu mới (hiệu lực 1 giờ, dùng một lần):\n\n" + link
                            + "\n\nNếu bạn không yêu cầu, hãy bỏ qua email này: mật khẩu của bạn chưa thay đổi.\n");
        }
    }

    private static boolean en(String locale) {
        return "en".equals(locale);
    }

    private static String greeting(String name, boolean en) {
        String n = name == null || name.isBlank() ? null : name.strip();
        if (en) {
            return (n == null ? "Hello," : "Hello " + n + ",") + "\n\n";
        }
        return (n == null ? "Xin chào," : "Xin chào " + n + ",") + "\n\n";
    }

    private void enqueue(String kind, String to, String subject, String text) {
        try {
            executor.execute(() -> send(kind, to, subject, text));
        } catch (RejectedExecutionException e) {
            LOG.warn("auth mail '{}' dropped: queue full", kind);
        }
    }

    private void send(String kind, String to, String subject, String text) {
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, false);
            sender.send(message);
        } catch (Exception e) {
            // class name only: the message of a mail exception may quote the address or server reply
            LOG.warn("auth mail '{}' could not be sent: {}", kind, e.getClass().getSimpleName());
        }
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }
}
