package com.english9x.identity.internal;

import com.english9x.identity.api.InvitationMailer;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
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
class SmtpAuthMailer implements AuthMailer, InvitationMailer, DisposableBean {

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
                    greeting(displayName, true) + "Someone signed up to English9x with this email address. Clicking the link below confirms that account. "
                            + "If you did not sign up yourself, do not click: someone else may have registered it, and you can ignore this message.\n\n"
                            + "If it was you, confirm your email address with this link (valid for 24 hours):\n\n" + link + "\n");
        } else {
            enqueue("verification", to, "Xác thực email tài khoản English9x",
                    greeting(displayName, false) + "Có người vừa đăng ký English9x bằng địa chỉ email này. Bấm vào liên kết bên dưới là xác nhận tài khoản đó. "
                            + "Nếu không phải bạn tự đăng ký, đừng bấm: có thể người khác đã đăng ký, và bạn có thể bỏ qua email này.\n\n"
                            + "Nếu đúng là bạn, hãy xác thực địa chỉ email bằng liên kết sau (có hiệu lực 24 giờ):\n\n" + link + "\n");
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
    public void sendGoogleLinked(String to, String locale) {
        String forgot = baseUrl + "/forgot-password";
        if (en(locale)) {
            enqueue("google-linked", to, "Google sign-in was linked to your English9x account",
                    greeting(null, true) + "Google sign-in is now linked to the English9x account with this email address. For your safety every other "
                            + "device was signed out, and any password set on this account no longer works, because someone else may have registered it "
                            + "with your address.\n\nYou can keep signing in with Google. To have a password again, reset your password here:\n\n" + forgot + "\n");
        } else {
            enqueue("google-linked", to, "Đã liên kết đăng nhập Google với tài khoản English9x",
                    greeting(null, false) + "Đăng nhập bằng Google vừa được liên kết với tài khoản English9x của địa chỉ email này. Để an toàn, mọi thiết bị "
                            + "khác đã được đăng xuất và mật khẩu đã đặt cho tài khoản này không còn dùng được, vì có thể người khác đã đăng ký bằng email của bạn."
                            + "\n\nBạn vẫn đăng nhập được bằng Google. Muốn có lại mật khẩu, hãy đặt lại mật khẩu tại đây:\n\n" + forgot + "\n");
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

    @Override
    public void sendInvitation(String to, String locale, String inviterName, String orgName, List<String> roles, String token) {
        String link = baseUrl + "/accept-invitation?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        boolean en = en(locale);
        String who = inviterName == null || inviterName.isBlank() ? null : inviterName.strip();
        String where = orgName == null ? "English9x" : orgName.strip() + " (English9x)";
        String what = String.join(", ", roles.stream().map(r -> roleLabel(r, en)).toList());
        if (en) {
            enqueue("invitation", to, "Invitation to join " + where,
                    greeting(null, true) + (who == null ? "You" : who + " invited you") + " to join " + where + " as: " + what + ".\n\n"
                            + "Sign in (or create an account) with THIS email address, then open the link below to accept. It works once and expires:\n\n"
                            + link + "\n\nIf you were not expecting this, ignore this message: nothing happens until the link is opened.\n");
        } else {
            enqueue("invitation", to, "Lời mời tham gia " + where,
                    greeting(null, false) + (who == null ? "Bạn" : who + " mời bạn") + " tham gia " + where + " với vai trò: " + what + ".\n\n"
                            + "Hãy đăng nhập (hoặc tạo tài khoản) bằng ĐÚNG địa chỉ email này, rồi mở liên kết dưới đây để chấp nhận. Liên kết chỉ dùng một lần và có hạn:\n\n"
                            + link + "\n\nNếu bạn không mong đợi lời mời này, hãy bỏ qua email: không có gì xảy ra cho đến khi liên kết được mở.\n");
        }
    }

    private static String roleLabel(String roleAndExam, boolean en) {
        String[] parts = roleAndExam.split("/", 2);
        String role = parts[0];
        String name = switch (role) {
            case "LEARNER" -> en ? "Learner" : "Học viên";
            case "AUTHOR" -> en ? "Author" : "Biên soạn";
            case "REVIEWER" -> en ? "Reviewer" : "Người duyệt";
            case "TEACHER" -> en ? "Teacher" : "Giáo viên";
            case "ORG_ADMIN" -> en ? "Organisation admin" : "Quản trị tổ chức";
            case "SYS_ADMIN" -> en ? "System admin" : "Quản trị hệ thống";
            default -> role;
        };
        return parts.length > 1 ? name + " " + parts[1] : name;
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
