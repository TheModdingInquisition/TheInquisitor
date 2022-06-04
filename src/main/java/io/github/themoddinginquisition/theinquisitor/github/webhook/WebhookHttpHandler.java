package io.github.themoddinginquisition.theinquisitor.github.webhook;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.themoddinginquisition.theinquisitor.github.webhook.event.WebhookEventType;
import io.github.themoddinginquisition.theinquisitor.github.webhook.handler.WebhookEventHandler;
import io.github.themoddinginquisition.theinquisitor.util.io.MacInputStream;
import io.github.themoddinginquisition.theinquisitor.util.io.RequestMethodFilter;
import io.github.themoddinginquisition.theinquisitor.util.io.RequiredHeadersFilter;
import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.HashSetValuedHashMap;
import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.GitHub;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class WebhookHttpHandler implements HttpHandler {
    public static final String GITHUB_DELIVERY_GUID_HEADER = "X-GitHub-Delivery";
    public static final String GITHUB_EVENT_HEADER = "X-GitHub-Event";
    public static final String GITHUB_SIGNATURE_HEADER = "X-Hub-Signature-256";

    private final byte @Nullable [] secretToken;
    private final MultiValuedMap<WebhookEventType<?>, WebhookEventHandler<?>> eventHandlers = new HashSetValuedHashMap<>();

    public WebhookHttpHandler(byte @Nullable [] secretToken) {
        this.secretToken = secretToken;
    }

    public WebhookHttpHandler() {
        this(null);
    }

    @CanIgnoreReturnValue
    public WebhookHttpHandler bind(HttpContext context) {
        context.getFilters().add(new RequestMethodFilter("POST"));
        context.getFilters().add(new RequiredHeadersFilter(GITHUB_EVENT_HEADER, GITHUB_DELIVERY_GUID_HEADER));
        context.setHandler(this);
        return this;
    }

    public <T> WebhookHttpHandler addHandler(WebhookEventType<T> type, WebhookEventHandler<T> handler) {
        eventHandlers.put(type, handler);
        return this;
    }

    public WebhookHttpHandler removeHandler(WebhookEventHandler<?> eventHandler) {
        eventHandlers.asMap().forEach((type, col) -> col.remove(eventHandler));
        return this;
    }

    public WebhookHttpHandler removeHandlers(WebhookEventType<?> type) {
        eventHandlers.remove(type);
        return this;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!validateSignatures(exchange)) {
            try (final var os = exchange.getResponseBody()) {
                final String message = "Request signature is not present, or invalid!\n";
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_FORBIDDEN, message.length());
                os.write(message.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        final @Nullable String event = exchange.getRequestHeaders().getFirst(GITHUB_EVENT_HEADER);
        if (event == null) {
            final var response = "Missing " + GITHUB_EVENT_HEADER + " request header.\n";
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, response.length());
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        final var eventType = WebhookEventType.getEventType(event);
        if (eventType == null) {
            final var response = "Unknown event " + event + ".\n";
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, response.length());
            try (final var output = exchange.getResponseBody()) {
                output.write(response.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        final @Nullable String guid = exchange.getRequestHeaders().getFirst(GITHUB_DELIVERY_GUID_HEADER);
        if (guid == null) {
            final String response = "Missing " + GITHUB_DELIVERY_GUID_HEADER + " request header.\n";
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, response.length());
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        final UUID deliveryID = UUID.fromString(guid);

        final var handlers = eventHandlers.get(eventType);
        if (handlers.isEmpty()) {
            final var response = "This webhook handler does not recognize '" + event + "' events.\n";
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, response.length());
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        try (final var is = exchange.getRequestBody(); final var os = exchange.getResponseBody();) {
            final var payload = GitHub.getMappingObjectReader().readValue(is, eventType.getEventClass());
            if (is.available() > 0) {
                System.err.println("Bytes remaining after reading event payload: " + is.available());
                is.transferTo(OutputStream.nullOutputStream());
            }
            final AtomicBoolean isHandled = new AtomicBoolean();
            final WebhookEventHandler.Context output = new WebhookEventHandler.Context() {
                @Override
                public void respond(int statusCode, String message) throws IOException {
                    exchange.sendResponseHeaders(statusCode, message.length());
                    os.write(message.getBytes(StandardCharsets.UTF_8));
                }

                @Override
                public void setHandled(boolean handled) {
                    isHandled.set(true);
                }
            };
            for (final var handler : handlers) {
                doHandle(deliveryID, handler, output, payload);
                if (isHandled.get())
                    break;
            }
            if (!isHandled.get()) {
                output.respond(HttpURLConnection.HTTP_ACCEPTED, "Note: the event was not handled, but it was accepted\n");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void doHandle(UUID deliveryID, WebhookEventHandler<T> handler, WebhookEventHandler.Context output, Object payload) throws IOException {
        handler.handleEvent(deliveryID, output, (T) payload);
    }

    private static final String HMAC_SHA256 = "HmacSHA256";

    private boolean validateSignatures(HttpExchange exchange) throws IOException {
        if (secretToken != null) { // Validate signatures
            Mac mac;
            try {
                mac = Mac.getInstance(HMAC_SHA256);
                mac.init(new SecretKeySpec(secretToken, HMAC_SHA256));
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("Failed to create mac for SHA-256; should be impossible as it's JDK-mandated to exist", e);
            } catch (InvalidKeyException e) {
                throw new IOException("Failed to initialize mac with key; should be impossible as they both are for SHA-256", e);
            }

            final String ghSignature = exchange.getRequestHeaders().getFirst(GITHUB_SIGNATURE_HEADER);
            return compareSignatures(new MacInputStream(mac, exchange.getRequestBody()), ghSignature);
        }
        return true;
    }

    private boolean compareSignatures(MacInputStream inputStream, String expected) throws IOException {
        String actual = "sha256=" + MacInputStream.bytesToHex(inputStream.getMac().doFinal());
        return actual.equals(expected);
    }

}
