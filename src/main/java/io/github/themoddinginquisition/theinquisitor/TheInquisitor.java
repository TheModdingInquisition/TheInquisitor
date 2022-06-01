package io.github.themoddinginquisition.theinquisitor;

import com.freya02.botcommands.api.CommandsBuilder;
import io.github.cdimascio.dotenv.Dotenv;
import io.github.themoddinginquisition.theinquisitor.commands.LinkGitHub;
import io.github.themoddinginquisition.theinquisitor.util.DotenvLoader;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.events.Event;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.utils.AllowedMentions;
import org.flywaydb.core.Flyway;
import org.jasypt.util.text.AES256TextEncryptor;
import org.jdbi.v3.core.Jdbi;
import org.jetbrains.annotations.NotNull;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public class TheInquisitor {

    public static final String GITHUB_APP_CLIENT_ID = "github_app_client_id";

    private static TheInquisitor instance;

    public static void main(String[] args) throws Exception {
        instance = new TheInquisitor(args.length < 1 ? Path.of("") : Path.of(args[0]));
    }

    public static TheInquisitor getInstance() {
        return instance;
    }

    private final Path rootPath;
    private final Dotenv dotenv;
    private final JDA jda;
    private final Jdbi jdbi;
    private final GitHub github;
    private final AES256TextEncryptor encryptor;

    private TheInquisitor(Path rootPath) throws Exception {
        this.rootPath = rootPath;

        this.dotenv = DotenvLoader.builder()
                .filePath(rootPath.resolve(".env"))
                .whenCreated(writer -> writer
                        .writeComment("The bot's Discord token")
                        .writeValue("discord_token", "")

                        .writeComment("The bot's GitHub token")
                        .writeValue("github_token", "")
                        .writeComment("The GitHub app client id")
                        .writeValue(GITHUB_APP_CLIENT_ID, "")

                        .writeComment("The encryption password used for encrypting GitHub Oauth tokens")
                        .writeValue("encryption_password", "dummy")
                )
                .load();

        this.encryptor = new AES256TextEncryptor();
        encryptor.setPassword(dotenv.get("encryption_password"));

        this.github = new GitHubBuilder()
                .withOAuthToken(dotenv.get("github_token"))
                .build();

        this.jda = JDABuilder.createLight(dotenv.get("discord_token"))
                .addEventListeners(
                        consumerEvent(ButtonInteractionEvent.class, LinkGitHub::buttonInteraction)
                )
                .build()
                .awaitReady();

        AllowedMentions.setDefaultMentionRepliedUser(false);

        CommandsBuilder.newBuilder()
                .textCommandBuilder(cmd -> cmd.addPrefix("+")) // TODO no hardcoding
                .build(jda, TheInquisitor.class.getPackageName() + ".commands");

        // Setup database
        {
            final var dbPath = rootPath.resolve("data.db");
            if (!Files.exists(dbPath)) {
                try {
                    Files.createFile(dbPath);
                } catch (IOException e) {
                    throw new RuntimeException("Exception creating database!", e);
                }
            }
            final var url = "jdbc:sqlite:" + dbPath;
            SQLiteDataSource dataSource = new SQLiteDataSource();
            dataSource.setUrl(url);
            dataSource.setDatabaseName("TheInquisitor");

            final var flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db")
                    .load();
            flyway.migrate();

            jdbi = Jdbi.create(dataSource);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(jda::shutdownNow, "ShutdownHook"));
    }

    public AES256TextEncryptor getEncryptor() {
        return encryptor;
    }

    public Dotenv getDotenv() {
        return dotenv;
    }

    private static <T extends Event> EventListener consumerEvent(Class<T> eventClass, Consumer<T> consumer) {
        return new EventListener() {
            @Override
            public void onEvent(@NotNull GenericEvent event) {
                if (eventClass.isInstance(event))
                    consumer.accept(eventClass.cast(event));
            }
        };
    }
}
