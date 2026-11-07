package com.english9x.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.english9x.notification.api.EventType;
import com.english9x.notification.api.NotificationService;
import com.english9x.support.SmtpSink;
import com.english9x.support.TestAuth;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The notification email through the real SMTP path: language, layout, escaping, and the rule that security mail ignores preferences. */
class EmailChannelIT extends NotificationIT {

    @Autowired NotificationService notifications;

    @Test
    void rendersVietnameseTemplate() {
        UUID u = user("vi", "vi", "Nguyễn Văn An");
        notifications.notify(EventType.REVIEW_REQUESTED, u, Map.of("contentType", "question", "examCode", "IELTS"), "vi1");
        SmtpSink.Mail mail = TestAuth.SMTP.await(emailOf(u));
        assertEquals("Có nội dung chờ duyệt", mail.subject());
        assertTrue(mail.body().startsWith("Xin chào Nguyễn Văn An,"), mail.body());
        assertTrue(mail.body().contains("Một nội dung (question) của kỳ thi IELTS đang chờ bạn duyệt."), mail.body());
        assertTrue(mail.body().contains(TestAuth.BASE_URL + "/notifications"), "link from e9x.public-base-url: " + mail.body());
        assertTrue(mail.body().contains("Tuỳ chọn thông báo"), "footer says how to switch it off");
        assertNotNull(mail.html(), "an HTML part too");
        assertTrue(mail.html().contains("lang=\"vi\""), mail.html());
        assertTrue(mail.html().contains("Một nội dung (question) của kỳ thi IELTS đang chờ bạn duyệt."), mail.html());
    }

    @Test
    void rendersEnglishForAnEnglishRecipient() {
        UUID u = user("en", "en", "Ann Lee");
        notifications.notify(EventType.CONTENT_PUBLISHED, u, Map.of("contentType", "test", "examCode", "TOEIC"), "en1");
        SmtpSink.Mail mail = TestAuth.SMTP.await(emailOf(u));
        assertEquals("Your content was published", mail.subject());
        assertTrue(mail.body().startsWith("Hello Ann Lee,"), mail.body());
        assertTrue(mail.body().contains("The content item (test) of the TOEIC exam that you wrote was approved and published."), mail.body());
        assertTrue(mail.html().contains("lang=\"en\""));
    }

    @Test
    void userControlledValuesAreEscapedInHtmlAndCannotInjectHeadersOrTemplates() {
        UUID u = user("xss", "vi", "<script>alert(1)</script> & \"Bob\"");
        notifications.notify(EventType.REVIEW_REQUESTED, u,
                Map.of("contentType", "<img src=x onerror=alert(1)>", "examCode", "{contentType}\r\nBcc: evil@example.test"), "xss1");
        SmtpSink.Mail mail = TestAuth.SMTP.await(emailOf(u));
        String html = mail.html();
        assertFalse(html.contains("<script"), html);
        assertFalse(html.contains("<img"), html);
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;Bob&quot;"), html);
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"), html);
        // one pass: a value that looks like a placeholder is shown as it is, not expanded again; control characters are flattened
        assertTrue(mail.body().contains("kỳ thi {contentType} Bcc: evil@example.test đang chờ"), mail.body());
        assertEquals("Có nội dung chờ duyệt", mail.subject());
    }

    @Test
    void anOnlyEmailPreferenceOffMeansNoMailButTheInAppRowStays() {
        UUID u = user("off");
        setPref(u, EventType.REVIEW_REQUESTED, "EMAIL", false);
        notifications.notify(EventType.REVIEW_REQUESTED, u, Map.of("contentType", "question", "examCode", "IELTS"), "off1");
        // nothing was planned: the preference is applied when the notification is created, so there is no delivery row and no job to wait for
        assertEquals(0, deliveries(u).size(), "no email delivery exists: " + deliveries(u));
        assertEquals(1, inAppCount(u));
        assertEquals(0, TestAuth.SMTP.to(emailOf(u)).size());
    }

    @Test
    void securityMailIsNeverSwitchedOffByThePreferences() {
        String email = "sec-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        Res r = post("/api/auth/register", Map.of("email", email, "password", "Sup3r-secret-pass", "displayName", "Sec", "consent", true), null);
        assertEquals(201, r.status(), r.raw());
        UUID id = UUID.fromString(su.queryForObject("select id::text from users where lower(email) = lower(?)", String.class, email));
        TestAuth.SMTP.awaitSubject(email, "Xác thực");
        for (EventType t : EventType.values()) {
            setPref(id, t, "EMAIL", false);
            setPref(id, t, "TELEGRAM", false);
        }
        int before = TestAuth.SMTP.to(email).size();
        assertEquals(202, post("/api/auth/password-reset/request", Map.of("email", email), null).status());
        SmtpSink.Mail reset = TestAuth.SMTP.await(email, before);
        assertTrue(reset.subject().contains("Đặt lại mật khẩu"), reset.subject());
    }

    @Test
    void anUnverifiedAddressGetsNoNotificationMailButKeepsTheInAppRow() {
        UUID u = user("unverified");
        su.update("update users set email_verified_at = null where id = ?", u);
        notifications.notify(EventType.REVIEW_REQUESTED, u, Map.of("contentType", "question", "examCode", "IELTS"), "unv1");
        // the recorded outcome of the job is the proof: once it is SKIPPED the worker has finished with it and sent nothing
        awaitDeliveryStatus(u, "EMAIL", "SKIPPED");
        assertEquals("email-unverified", su.queryForObject("select d.last_error from notification_deliveries d join notifications n on n.id = d.notification_id "
                + "where n.user_id = ? and d.channel = 'EMAIL'", String.class, u));
        assertEquals(0, TestAuth.SMTP.to(emailOf(u)).size(), "no mail to an unverified address");
        assertEquals(1, inAppCount(u), "the in-app notification is unaffected");
    }

    @Test
    void theSameAddressGetsMailOnceItIsVerified() {
        UUID u = user("later");
        su.update("update users set email_verified_at = null where id = ?", u);
        notifications.notify(EventType.REVIEW_REQUESTED, u, Map.of("contentType", "question", "examCode", "IELTS"), "later1");
        awaitDeliveryStatus(u, "EMAIL", "SKIPPED");
        su.update("update users set email_verified_at = now() where id = ?", u);
        notifications.notify(EventType.REVIEW_REQUESTED, u, Map.of("contentType", "question", "examCode", "IELTS"), "later2");
        assertNotNull(TestAuth.SMTP.await(emailOf(u)));
        assertEquals(1, TestAuth.SMTP.to(emailOf(u)).size(), "only the one created after verification");
    }
}
