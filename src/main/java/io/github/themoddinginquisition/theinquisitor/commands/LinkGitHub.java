package io.github.themoddinginquisition.theinquisitor.commands;

import com.freya02.botcommands.api.application.ApplicationCommand;
import com.freya02.botcommands.api.application.slash.GlobalSlashEvent;
import com.freya02.botcommands.api.application.slash.annotations.JDASlashCommand;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonObject;
import io.github.themoddinginquisition.theinquisitor.TheInquisitor;
import io.github.themoddinginquisition.theinquisitor.util.Constants;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

public class LinkGitHub extends ApplicationCommand {

    private static final Cache<Long, String> USER_2_CODE_MAP = Caffeine
            .newBuilder()
            .expireAfterAccess(Duration.of(15, ChronoUnit.MINUTES))
            .build();

    private static final String VERIFY_BUTTON = "link_verify";
    private static final String CREATE_CODE_ENDPOINT = "https://github.com/login/device/code";
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @JDASlashCommand(name = "link", description = "Links your Discord account with your GitHub one")
    public void link(GlobalSlashEvent event) {
        try {
            final var body = new JsonObject();

            body.addProperty("client_id", TheInquisitor.getInstance().getDotenv().get(TheInquisitor.GITHUB_APP_CLIENT_ID));
            body.addProperty("scope", "read:user");

            final var request = HttpRequest.newBuilder(URI.create(CREATE_CODE_ENDPOINT))
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.getAsString()))
                    .build();

            final var response = Constants.GSON.fromJson(CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body(), JsonObject.class);
            final var deviceCode = response.get("device_code").getAsString();
            USER_2_CODE_MAP.put(event.getUser().getIdLong(), deviceCode);
        } catch (Exception e) {
            event.reply("There was an exception executing that command: " + e)
                    .setEphemeral(true)
                    .queue();
        }
    }

    public static void buttonInteraction(final ButtonInteractionEvent event) {
        if (!Objects.equals(VERIFY_BUTTON, event.getButton().getId()))
            return;

        final var ownerId = requireNonNull(event.getMessage().getInteraction()).getMember().getIdLong();

    }
}
