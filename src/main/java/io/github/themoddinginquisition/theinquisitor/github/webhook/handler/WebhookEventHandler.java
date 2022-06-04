package io.github.themoddinginquisition.theinquisitor.github.webhook.handler;

import java.io.IOException;
import java.util.UUID;

public interface WebhookEventHandler<T> {

    void handleEvent(UUID deliveryID, Context context, T event) throws IOException;

    interface Context {
        void respond(int statusCode, String message) throws IOException;

        void setHandled(boolean handled);
    }
}