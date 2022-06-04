package io.github.themoddinginquisition.theinquisitor.github.webhook.handler;

import io.github.themoddinginquisition.theinquisitor.github.webhook.event.PingEvent;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.UUID;

public class PingHandler implements WebhookEventHandler<PingEvent> {
    @Override
    public void handleEvent(UUID deliveryID, Context context, PingEvent event) throws IOException {
        context.respond(HttpURLConnection.HTTP_ACCEPTED, "Pong!");
        context.setHandled(true);
    }
}
