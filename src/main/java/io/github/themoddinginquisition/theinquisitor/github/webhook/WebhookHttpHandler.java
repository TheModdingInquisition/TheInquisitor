package io.github.themoddinginquisition.theinquisitor.github.webhook;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.themoddinginquisition.theinquisitor.github.webhook.event.WebhookEventType;
import io.github.themoddinginquisition.theinquisitor.github.webhook.handler.WebhookEventHandler;
import io.github.themoddinginquisition.theinquisitor.util.io.CallbackInputStream;
import io.github.themoddinginquisition.theinquisitor.util.io.MacInputStream;
import io.github.themoddinginquisition.theinquisitor.util.io.RequestMethodFilter;
import io.github.themoddinginquisition.theinquisitor.util.io.RequiredHeadersFilter;
import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.HashSetValuedHashMap;
import org.jdbi.v3.core.extension.ExtensionConsumer;
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
import java.util.function.Consumer;

public class WebhookHttpHandler implements HttpHandler {
    public static final String GITHUB_DELIVERY_GUID_HEADER = "X-GitHub-Delivery";
    public static final String GITHUB_EVENT_HEADER = "X-GitHub-Event";
    public static final String GITHUB_SIGNATURE_HEADER = "X-Hub-Signature-256";

    private final byte @Nullable [] secretToken;
    private final boolean errorOnSignatureMismatch;
    private final MultiValuedMap<WebhookEventType<?>, WebhookEventHandler<?>> eventHandlers = new HashSetValuedHashMap<>();

    public WebhookHttpHandler(byte @Nullable [] secretToken, boolean errorOnSignatureMismatch) {
        this.secretToken = secretToken;
        this.errorOnSignatureMismatch = errorOnSignatureMismatch;
    }

    public WebhookHttpHandler() {
        this(null, false);
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
        validateSignatures(exchange);

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
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void doHandle(UUID deliveryID, WebhookEventHandler<T> handler, WebhookEventHandler.Context output, Object payload) throws IOException {
        handler.handleEvent(deliveryID, output, (T) payload);
    }

    private static final String HMAC_SHA256 = "HmacSHA256";

    private void validateSignatures(HttpExchange exchange) throws IOException {
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
            exchange.setStreams(
                    new CallbackInputStream<>(new MacInputStream(mac, exchange.getRequestBody()),
                            rethrow((MacInputStream in) -> compareSignatures(in, ghSignature))),
                    exchange.getResponseBody());
        }
    }

    private static <T> Consumer<T> rethrow(ExtensionConsumer<T, Exception> cons) {
        return obj -> {
            try {
                cons.useExtension(obj);
            } catch (Exception e) {
                sneakyThrow(e);
            }
        };
    }

    private void compareSignatures(MacInputStream inputStream, String expected) throws IOException {
        String actual = "sha256=" + MacInputStream.bytesToHex(inputStream.getMac().doFinal());
        if (!actual.equals(expected)) {
            System.err.printf("Signatures do not match: expected '%s', actual '%s'%n", expected, actual);
            if (errorOnSignatureMismatch) {
                throw new IOException("Signatures do not match: expected '" + expected + "', actual '" + actual + "'");
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static <E extends Throwable> void sneakyThrow(Throwable ex) throws E {
        throw (E) ex;
    }

}
