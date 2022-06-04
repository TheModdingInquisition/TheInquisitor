package io.github.themoddinginquisition.theinquisitor.github.webhook.handler;

import java.io.IOException;
import java.util.UUID;

/**
 * A handler for GitHub webhook events.
 *
 * @param <T> the type of the event
 */
public interface WebhookEventHandler<T> {

    /**
     * Handles the event
     *
     * @param deliveryID the delivery ID of the event
     * @param context    the context
     * @param event      the sent event
     * @throws IOException if an exception occurs handling that event
     */
    void handleEvent(UUID deliveryID, Context context, T event) throws IOException;

    interface Context {
        /**
         * Responds to the request.
         *
         * @param statusCode the status code to respond with. Use {@link java.net.HttpURLConnection}
         * @param message    the message to respond with
         * @throws IOException if an exception occurs responding
         */
        void respond(int statusCode, String message) throws IOException;

        /**
         * Marks this event as handled. <br>
         * Once handled, any other event listeners will not fire for that event.
         *
         * @param handled if the event is handled
         */
        void setHandled(boolean handled);
    }
}