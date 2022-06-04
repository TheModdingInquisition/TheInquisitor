package io.github.themoddinginquisition.theinquisitor.github.webhook.event;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

@SuppressWarnings("ClassCanBeRecord")
public final class WebhookEventType<T> {

    private static final BiMap<String, WebhookEventType<?>> REGISTRY = HashBiMap.create();

    public static WebhookEventType<?> getEventType(String name) {
        return REGISTRY.get(name);
    }

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