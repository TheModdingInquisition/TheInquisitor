package io.github.themoddinginquisition.theinquisitor.util.io;

import javax.crypto.Mac;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MacInputStream extends FilterInputStream {
    private final Mac mac;

    public MacInputStream(Mac mac, InputStream in) {
        super(in);
        this.mac = mac;
    }

    public Mac getMac() {
        return mac;
    }

    @Override
    public int read() throws IOException {
        final int value = super.read();
        if (value != -1) { // If not -1 (end of stream), then value from 0-255, so cast loses no data
            mac.update((byte) value);
        }
        return value;
    }

    private static final byte[] HEX_ARRAY = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    public static String bytesToHex(byte[] bytes) {
        byte[] hexChars = new byte[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars, StandardCharsets.UTF_8);
    }
}