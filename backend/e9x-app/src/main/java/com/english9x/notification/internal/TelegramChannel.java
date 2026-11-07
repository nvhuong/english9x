package com.english9x.notification.internal;

import com.english9x.notification.api.Channel;
import com.english9x.notification.api.Message;
import com.english9x.notification.api.NotificationChannel;
import com.english9x.notification.api.Recipient;
import org.springframework.stereotype.Component;

/**
 * Telegram: the title in bold, the body, and a link, in Telegram's HTML subset. Everything that came from a person or from event data is
 * run through {@link Escape#telegram} first, so a value cannot add a tag, a link or a mention. The recipient is the chat linked through
 * {@code /start <token>}.
 */
@Component
class TelegramChannel implements NotificationChannel {

    private final TelegramClient client;
    private final Templates templates;

    TelegramChannel(TelegramClient client, Templates templates) {
        this.client = client;
        this.templates = templates;
    }

    @Override
    public Channel id() {
        return Channel.TELEGRAM;
    }

    @Override
    public void send(Recipient to, Message m) throws Exception {
        if (to.chatId() == null) {
            throw new PermanentDeliveryException("not-linked");
        }
        String open = templates.text("telegram.openLink", to.locale());
        String text = "<b>" + Escape.telegram(m.title()) + "</b>\n" + Escape.telegram(m.body())
                + "\n\n<a href=\"" + Escape.telegram(m.link()) + "\">" + Escape.telegram(open) + "</a>";
        client.sendMessage(to.chatId(), text, true);
    }
}
