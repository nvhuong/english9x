package com.english9x.notification.internal;

import com.english9x.notification.api.Channel;
import com.english9x.notification.api.Message;
import com.english9x.notification.api.NotificationChannel;
import com.english9x.notification.api.Recipient;
import org.springframework.stereotype.Component;

/**
 * The in-app channel is the stored notification itself: it exists, in the same transaction as the call to
 * {@code NotificationService.notify}, before any other channel is even planned, and it cannot be switched off. There is nothing to push, so
 * {@link #send} has nothing to do; the class exists so that the three channels of spec §13 are listed uniformly
 * (see {@link Channel#optional()}).
 */
@Component
class InAppChannel implements NotificationChannel {

    @Override
    public Channel id() {
        return Channel.IN_APP;
    }

    @Override
    public void send(Recipient recipient, Message message) {
        // intentionally empty: see the class comment
    }
}
