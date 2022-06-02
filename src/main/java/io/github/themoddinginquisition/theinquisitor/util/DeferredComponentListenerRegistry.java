package io.github.themoddinginquisition.theinquisitor.util;

import com.matyrobbrt.jdahelper.components.ComponentListener;
import com.matyrobbrt.jdahelper.components.ComponentManager;
import com.matyrobbrt.jdahelper.components.storage.ComponentStorage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DeferredComponentListenerRegistry {
    private ComponentManager manager;
    private final List<ComponentListener> deferredListeners = Collections.synchronizedList(new ArrayList<>());

    public ComponentManager createManager(final ComponentStorage storage) {
        this.manager = new ComponentManager(storage, deferredListeners);
        return manager;
    }

    public ComponentListener.Builder createListener(final String featureId) {
        return ComponentListener.builder(featureId, listener -> {
            if (manager == null) {
                deferredListeners.add(listener);
            } else {
                manager.addListener(listener);
            }
        });
    }
}