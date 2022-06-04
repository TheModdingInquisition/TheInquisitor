package io.github.themoddinginquisition.theinquisitor;

import com.electronwill.nightconfig.core.file.FileWatcher;
import com.jagrosh.jdautilities.command.CommandClientBuilder;
import com.matyrobbrt.jdahelper.DismissListener;
import com.matyrobbrt.jdahelper.components.ComponentListener;
import com.matyrobbrt.jdahelper.components.ComponentManager;
import com.matyrobbrt.jdahelper.components.storage.ComponentStorage;
import io.github.cdimascio.dotenv.Dotenv;
import io.github.themoddinginquisition.theinquisitor.commands.ForkCommand;
import io.github.themoddinginquisition.theinquisitor.commands.LinkGitHubCommand;
import io.github.themoddinginquisition.theinquisitor.commands.pr.ManagedPRs;
import io.github.themoddinginquisition.theinquisitor.commands.pr.PRCommand;
import io.github.themoddinginquisition.theinquisitor.github.GitHubUserCache;
import io.github.themoddinginquisition.theinquisitor.util.Config;
import io.github.themoddinginquisition.theinquisitor.util.Constants;
import io.github.themoddinginquisition.theinquisitor.util.DeferredComponentListenerRegistry;
import io.github.themoddinginquisition.theinquisitor.util.DotenvLoader;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.utils.AllowedMentions;
import org.flywaydb.core.Flyway;
import org.jasypt.util.text.AES256TextEncryptor;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class TheInquisitor {

    public static final String GITHUB_APP_CLIENT_ID = "github_app_client_id";
    public static final Logger LOGGER = LoggerFactory.getLogger("TheInquisitor");
    public static final FileWatcher WATCHER = FileWatcher.defaultInstance();

    private static TheInquisitor instance;

    public static void main(String[] args) throws Exception {
        instance = new TheInquisitor(args.length < 1 ? Path.of("") : Path.of(args[0]));
    }

    private static final DeferredComponentListenerRegistry LISTENER_REGISTRY = new DeferredComponentListenerRegistry();
    public static ComponentListener.Builder getComponentListener(String featureId) {
        return LISTENER_REGISTRY.createListener(featureId);
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
    private Config config;
    private final GitHubUserCache gitHubUserCache;
    private final ComponentManager componentManager;

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

        final var cfgPath = rootPath.resolve("config.json");

        if (!Files.exists(cfgPath)) {
            Files.writeString(cfgPath, Constants.GSON.toJson(new Config()));
        }
        try (final var reader = Files.newBufferedReader(cfgPath)) {
            this.config = Constants.GSON.fromJson(reader, Config.class);
        }
        WATCHER.addWatch(cfgPath, () -> {
            try (final var reader = Files.newBufferedReader(cfgPath)) {
                this.config = Constants.GSON.fromJson(reader, Config.class);
            } catch (IOException e) {
                LOGGER.error("Exception trying to reload config {}: ", cfgPath, e);
            }
            LOGGER.info("Reloaded config file {}", cfgPath);
        });

        this.encryptor = new AES256TextEncryptor();
        encryptor.setPassword(dotenv.get("encryption_password"));

        this.github = new GitHubBuilder()
                .withOAuthToken(dotenv.get("github_token"))
                .build();

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

            jdbi = Jdbi.create(dataSource)
                    .installPlugin(new SqlObjectPlugin());
        }

        // Components
        {
            final var storage = ComponentStorage.sql(jdbi, "components");
            this.componentManager = LISTENER_REGISTRY.createManager(storage);
        }

        final var managedPRs = new ManagedPRs(github, jdbi, this::getJDA, config.channel);

        final var commandClient = new CommandClientBuilder()
                .setOwnerId(0L)
                .addSlashCommands(
                        new LinkGitHubCommand(), new ForkCommand(), new PRCommand(managedPRs)
                )
                .forceGuildOnly(853270691176906802L)
                .build();

        this.jda = JDABuilder.createLight(dotenv.get("discord_token"))
                .addEventListeners(
                        commandClient, componentManager, new DismissListener()
                )
                .build()
                .awaitReady();

        AllowedMentions.setDefaultMentionRepliedUser(false);

        this.gitHubUserCache = new GitHubUserCache(github, jdbi, this::getConfig);

        Runtime.getRuntime().addShutdownHook(new Thread(jda::shutdownNow, "ShutdownHook"));

        final var pool = Executors.newScheduledThreadPool(2, r -> {
            final var thread = new Thread(r, "ScheduledAction");
            thread.setDaemon(true);
            return thread;
        });

        pool.scheduleAtFixedRate(() -> {
            try {
                managedPRs.run();
            } catch (Throwable e) {
                LOGGER.error("Exception trying to check for PR updates: ", e);
            }
        }, 1,30, TimeUnit.SECONDS); // TODO: config for checking period
        pool.scheduleAtFixedRate(() -> componentManager.removeComponentsOlderThan(30, ChronoUnit.MINUTES), 1, 30, TimeUnit.MINUTES);
    }

    public JDA getJDA() {
        return jda;
    }

    public AES256TextEncryptor getEncryptor() {
        return encryptor;
    }

    public Dotenv getDotenv() {
        return dotenv;
    }

    public Config getConfig() {
        return this.config;
    }

    public GitHubUserCache getGitHubUserCache() {
        return this.gitHubUserCache;
    }

    public Jdbi jdbi() {
        return jdbi;
    }

    public GitHub getGithub() {
        return github;
    }

    public ComponentManager getComponentManager() {
        return componentManager;
    }

}
