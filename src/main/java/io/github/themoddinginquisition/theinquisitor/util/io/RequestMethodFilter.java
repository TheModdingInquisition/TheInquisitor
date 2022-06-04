package io.github.themoddinginquisition.theinquisitor.util.io;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

public class RequestMethodFilter extends Filter {
    private final String requestMethod;

    public RequestMethodFilter(String requestMethod) {
        this.requestMethod = requestMethod;
    }

    @Override
    public void doFilter(HttpExchange exchange, Filter.Chain chain) throws IOException {
        if (!exchange.getRequestMethod().equals(requestMethod)) {
            String response = "Bad request method\n";
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_METHOD, response.length());
            try (final var output = exchange.getResponseBody()) {
                output.write(response.getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        chain.doFilter(exchange);
    }

    @Override
    public String description() {
        return "Rejects any method other than " + requestMethod;
    }
}