package io.github.themoddinginquisition.theinquisitor.util.io;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

public class CallbackInputStream<T extends InputStream> extends FilterInputStream {
    private final T inputStream;
    private final Consumer<T> streamEndCallback;

    public CallbackInputStream(T in, Consumer<T> streamEndCallback) {
        super(in);
        this.inputStream = in;
        this.streamEndCallback = streamEndCallback;
    }

    @Override
    public int read() throws IOException {
        final int value = super.read();
        if (value == -1) { // End of stream, callback!
            streamEndCallback.accept(inputStream);
        }
        return value;
    }
}
