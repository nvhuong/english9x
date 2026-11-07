package com.english9x.notification.internal;

import com.english9x.notification.api.Channel;
import com.english9x.notification.api.Message;
import com.english9x.notification.api.NotificationChannel;
import com.english9x.notification.api.Recipient;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Email over SMTP (Mailpit in E2E): a plain-text part and an HTML part, in the recipient's language. Every user-controlled string (the
 * display name, the event data already inside {@code message}) is escaped when the HTML is written; the HTML is assembled from fixed
 * markup plus {@link Escape#html}, never from a template that could carry markup. The subject is one line (values are flattened). This is
 * the optional notification mail; account security mail (verify, reset, already registered) goes through identity's own mailer and is
 * never subject to the preferences.
 */
@Component
class EmailChannel implements NotificationChannel {

    private final JavaMailSender sender;
    private final NotificationProperties props;
    private final Templates templates;

    EmailChannel(JavaMailSender sender, NotificationProperties props, Templates templates) {
        this.sender = sender;
        this.props = props;
        this.templates = templates;
    }

    @Override
    public Channel id() {
        return Channel.EMAIL;
    }

    @Override
    public void send(Recipient to, Message m) throws Exception {
        String locale = to.locale();
        String name = to.displayName() == null ? "" : Templates.oneLine(to.displayName());
        String greeting = name.isEmpty() ? templates.text("email.greetingAnonymous", locale)
                : templates.text("email.greeting", locale, java.util.Map.of("name", name));
        String openLink = templates.text("email.openLink", locale);
        String footer = templates.text("email.footer", locale);

        String plain = greeting + "\n\n" + m.body() + "\n\n" + openLink + "\n" + m.link() + "\n\n-- \n" + footer + "\n";
        String html = "<!doctype html><html lang=\"" + (Templates.english(locale) ? "en" : "vi") + "\"><body>"
                + "<p>" + Escape.html(greeting) + "</p>"
                + "<h2>" + Escape.html(m.title()) + "</h2>"
                + "<p>" + Escape.html(m.body()) + "</p>"
                + "<p>" + Escape.html(openLink) + " <a href=\"" + Escape.html(m.link()) + "\">" + Escape.html(m.link()) + "</a></p>"
                + "<hr><p style=\"color:#555;font-size:12px\">" + Escape.html(footer) + "</p>"
                + "</body></html>";

        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(props.mailFrom());
        helper.setTo(to.email());
        helper.setSubject(m.title());
        helper.setText(plain, html);
        sender.send(message);
    }
}
