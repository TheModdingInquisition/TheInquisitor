package io.github.themoddinginquisition.theinquisitor.github.webhook.handler;

import java.io.IOException;
import java.util.UUID;

public interface WebhookEventHandler<T> {

    void handleEvent(UUID deliveryID, T event) throws IOException;
}
