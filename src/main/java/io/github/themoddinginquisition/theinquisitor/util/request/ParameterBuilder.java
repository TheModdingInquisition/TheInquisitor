package io.github.themoddinginquisition.theinquisitor.util.request;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public class ParameterBuilder {

    private final Map<Object, Object> parameters = new HashMap<>();
    private final String url;

    public ParameterBuilder(String url) {
        this.url = url;
    }
    public static ParameterBuilder forUrl(String url) {
        return new ParameterBuilder(url);
    }

    public ParameterBuilder add(Object key, Object value) {
        this.parameters.put(key, value);
        return this;
    }

    public URI getURI() {
        final var url = new StringBuilder(this.url + "?");
        for (final var it = parameters.entrySet().iterator(); it.hasNext();) {
            final var entry = it.next();
            url.append(entry.getKey()).append("=").append(entry.getValue());
            if (it.hasNext()) {
                url.append("&");
            }
        }
        return URI.create(url.toString());
    }

}
