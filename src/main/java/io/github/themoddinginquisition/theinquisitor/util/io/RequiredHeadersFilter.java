package io.github.themoddinginquisition.theinquisitor.util.io;

import com.google.common.collect.Sets;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class RequiredHeadersFilter extends Filter {
    private final Set<String> requiredHeaders;

    public RequiredHeadersFilter(@Nonnull Set<String> requiredHeaders) {
        if (requiredHeaders.isEmpty())
            throw new IllegalArgumentException("requiredHeaders needs to be non-empty");
        this.requiredHeaders = requiredHeaders;
    }

    public RequiredHeadersFilter(String... requiredHeaders) {
        this(Sets.newHashSet(requiredHeaders));
    }

    @Override
    public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
        final HashSet<String> missingHeaders = new HashSet<>(requiredHeaders);
        missingHeaders.removeIf(str -> exchange.getRequestHeaders().containsKey(str));
        if (!missingHeaders.isEmpty()) { // Missing required headers
            String response = "Missing required headers: " + missingHeaders + "\n";
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_REQUEST, response.length());
            try (final var output = exchange.getResponseBody()) {
                output.write(response.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }

        chain.doFilter(exchange);
    }

    @Override
    public String description() {
        return "Requires the following headers: " + requiredHeaders;
    }
}