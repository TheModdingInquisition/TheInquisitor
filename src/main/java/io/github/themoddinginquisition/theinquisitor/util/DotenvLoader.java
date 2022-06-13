package io.github.themoddinginquisition.theinquisitor.util;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvBuilder;
import io.github.cdimascio.dotenv.DotenvException;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public class DotenvLoader {
    public static DotenvLoader builder() {
        return new DotenvLoader();
    }

    private boolean systemProperties = false;
    private boolean throwIfMalformed = true;
    private Consumer<DotenvWriter> whenCreated;

    /**
     * Sets the consumer which will be run when a .env file is created, if it doesn't exist
     *
     * @param whenCreated the consumer
     * @return this {@link DotenvLoader}
     */
    public DotenvLoader whenCreated(Consumer<DotenvWriter> whenCreated) {
        this.whenCreated = whenCreated;
        return this;
    }

    /**
     * Does not throw an exception when .env is malformed.
     *
     * @return this {@link DotenvLoader}
     */
    public DotenvLoader ignoreIfMalformed() {
        throwIfMalformed = false;
        return this;
    }

    /**
     * Sets each environment variable as system properties.
     *
     * @return this {@link DotenvLoader}
     */
    public DotenvLoader systemProperties() {
        systemProperties = true;
        return this;
    }

    /**
     * Load the contents of .env into the virtual environment.
     *
     * @return a new {@link Dotenv} instance
     * @throws DotenvException when an error occurs
     */
    public Dotenv load() throws DotenvException, IOException {
        final var filePath = Path.of(".env");
        if (!Files.exists(filePath)) {
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }
            Files.createFile(filePath);

            if (whenCreated != null) {
                try (final var pWriter = new PrintWriter(new FileWriter(filePath.toFile()))) {
                    whenCreated.accept(new DotenvWriter() {
                        @Override
                        public DotenvWriter writeValue(@NotNull final String key, @Nullable final String value) {
                            pWriter.println("%s=%s".formatted(key, value == null ? "" : value));
                            return this;
                        }

                        @Override
                        public DotenvWriter writeComment(@NotNull final String comment) {
                            pWriter.write("# ");
                            pWriter.println(comment);
                            return this;
                        }
                    });
                }
            }
        }

        final var builder = new DotenvBuilder();
        if (!throwIfMalformed) {
            builder.ignoreIfMalformed();
        }
        if (systemProperties) {
            builder.systemProperties();
        }
        return builder.load();
    }

    public interface DotenvWriter {
        DotenvWriter writeValue(@NotNull String key, @Nullable String value);

        DotenvWriter writeComment(@NotNull String comment);
    }
}
