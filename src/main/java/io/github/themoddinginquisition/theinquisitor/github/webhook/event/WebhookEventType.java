package io.github.themoddinginquisition.theinquisitor.github.webhook.event;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

@SuppressWarnings("ClassCanBeRecord")
public final class WebhookEventType<T> {

    private static final BiMap<String, WebhookEventType<?>> REGISTRY = HashBiMap.create();
    public static WebhookEventType<?> getEventType(String name) {
        return REGISTRY.get(name);
    }

    public static final WebhookEventType<PingEvent> PING = new WebhookEventType<>("ping", PingEvent.class);
    public static final WebhookEventType<PushEvent> PUSH = new WebhookEventType<>("push", PushEvent.class);

    private final String name;
    private final Class<T> clazz;

    private WebhookEventType(String name, Class<T> clazz) {
        this.name = name;
        this.clazz = clazz;
        REGISTRY.put(name, this);
    }

    @Override
    public String toString() {
        return name;
    }

    public Class<T> getEventClass() {
        return clazz;
    }
}
