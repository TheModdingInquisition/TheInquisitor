package io.github.themoddinginquisition.theinquisitor.commands;

import static java.util.Objects.requireNonNull;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonObject;
import com.jagrosh.jdautilities.command.SlashCommand;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import io.github.themoddinginquisition.theinquisitor.TheInquisitor;
import io.github.themoddinginquisition.theinquisitor.db.GithubOauthDAO;
import io.github.themoddinginquisition.theinquisitor.util.Constants;
import io.github.themoddinginquisition.theinquisitor.util.request.ParameterBuilder;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.utils.TimeFormat;
import org.kohsuke.github.GitHubBuilder;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public class LinkGitHubCommand extends BaseSlashCommand {

    private static final Cache<Long, String> USER_2_CODE_MAP = Caffeine
            .newBuilder()
            .expireAfterAccess(Duration.of(15, ChronoUnit.MINUTES))
            .build();

    private static final String VERIFY_BUTTON = "link_verify";
    private static final String CREATE_CODE_ENDPOINT = "https://github.com/login/device/code";
    private static final String VERIFY_CODE_ENDPOINT = "https://github.com/login/oauth/access_token";
    private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    public LinkGitHubCommand() {
        name = "link";
        help = "Links your Discord account with your GitHub one";
    }


    @Override
    protected void exec(SlashCommandEvent event) throws Throwable {
        final var request = HttpRequest.newBuilder(ParameterBuilder.forUrl(CREATE_CODE_ENDPOINT)
                    .add("client_id", TheInquisitor.getInstance().getDotenv().get(TheInquisitor.GITHUB_APP_CLIENT_ID))
                    .add("scope", "read:user")
                    .getURI())
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        final var response = Constants.GSON.fromJson(CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body(), JsonObject.class);
        final var deviceCode = response.get("device_code").getAsString();
        USER_2_CODE_MAP.put(event.getUser().getIdLong(), deviceCode);

        final var userCode = response.get("user_code").getAsString();
        final var url = response.get("verification_uri").getAsString();
        final var expirationInterval = response.get("expires_in").getAsInt();

        event.deferReply(true).setContent("""
                        A new code has been generated for linking your Discord and GitHub account!
                        Please visit <%s> and enter the following code: `%s`.
                        The code expires %s"""
                        .formatted(url, userCode, TimeFormat.RELATIVE.format(Instant.now().plus(expirationInterval, ChronoUnit.SECONDS))))
                .mentionRepliedUser(true)
                .addActionRow(Button.success(VERIFY_BUTTON, "Verify"))
                .queue();
    }

    public static void buttonInteraction(final ButtonInteractionEvent event) {
        if (!Objects.equals(VERIFY_BUTTON, event.getButton().getId()))
            return;

        final var ownerId = requireNonNull(event.getMessage().getInteraction()).getUser().getIdLong();
        final var code = USER_2_CODE_MAP.getIfPresent(ownerId);
        if (code == null) {
            event.deferReply(true).setContent("The code has expired!")
                    .queue();
            return;
        }

        try {
            final var request = HttpRequest.newBuilder(ParameterBuilder.forUrl(VERIFY_CODE_ENDPOINT)
                        .add("client_id", TheInquisitor.getInstance().getDotenv().get(TheInquisitor.GITHUB_APP_CLIENT_ID))
                        .add("grant_type", GRANT_TYPE)
                        .add("device_code", code)
                        .getURI())
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            final var response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println(response.body());

            final var res = Constants.GSON.fromJson(response.body(), JsonObject.class);

            if (res.has("error")) {
                final var error = res.get("error").getAsString();
                if (error.equals("authorization_pending")) {
                    event.deferReply(true).setContent("You haven't authorized the application!")
                            .queue();
                } else {
                    event.deferReply(true).setContent("There was an error validating your authorization: " + error)
                            .queue();
                    TheInquisitor.LOGGER.warn("Error trying to valid authorization for user {}: {} / {}: {}", ownerId, error, res.get("error_description"),
                            res.get("error_uri"));
                }
                return;
            }

            final var token = res.get("access_token").getAsString();
            final var gh = new GitHubBuilder()
                    .withJwtToken(token)
                    .build();

            event.deferReply(true)
                    .setContent("Successfully authenticated as `" + gh.getMyself().getName() + "`")
                    .queue();

            TheInquisitor.getInstance().jdbi().useExtension(GithubOauthDAO.class, dao -> dao.insertEncrypted(ownerId, token));
        } catch (Exception e) {
            event.deferReply(true).setContent("There was an error handling that interaction: " + e).queue();
            TheInquisitor.LOGGER.error("Exception trying to verify user authentication: ", e);
        }
    }

}
