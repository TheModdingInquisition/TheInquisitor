package io.github.themoddinginquisition.theinquisitor.github.webhook.handler;

import io.github.themoddinginquisition.theinquisitor.TheInquisitor;
import io.github.themoddinginquisition.theinquisitor.commands.pr.ManagedPRs;
import io.github.themoddinginquisition.theinquisitor.github.webhook.event.PushEvent;
import net.dv8tion.jda.api.EmbedBuilder;

import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

public class PushHandler implements WebhookEventHandler<PushEvent> {

    public static final int COMMIT_COLOR = 0x7288da;

    @Override
    public void handleEvent(UUID deliveryID, Context context, PushEvent event) throws IOException {
        final var branch = event.ref().substring("refs/heads/".length());
        final var fullRef = (event.repository().getFullName() + "/" + branch).toLowerCase(Locale.ROOT);
        if (ManagedPRs.BRANCH_TO_THREAD.containsKey(fullRef)) {
            final var threadId = ManagedPRs.BRANCH_TO_THREAD.get(fullRef);
            final var thread = TheInquisitor.getInstance().getJDA().getThreadChannelById(threadId);
            if (thread == null) {
                context.setHandled(false);
                return;
            }
            thread.sendMessageEmbeds(generateEmbed(event, branch).build()).queue();
            context.setHandled(true);
        }
        context.setHandled(false);
    }

    private EmbedBuilder generateEmbed(PushEvent event, String branch) throws IOException {
        final var size = event.commits().length;
        final StringBuilder description = new StringBuilder();
        for (final var commit : event.commits()) {
            description.append("`[%s](%s)` %s - %s".formatted(
                    commit.getSHA1().substring(0, 7),
                    commit.getHtmlUrl(),
                    ManagedPRs.limit(commit.getCommitShortInfo().getMessage(), 50),
                    commit.getAuthor().getLogin()
            ));
        }
        return new EmbedBuilder()
                .setColor(COMMIT_COLOR)
                .setTimestamp(Instant.now())
                .setTitle("[" + event.repository().getName() + ":" + branch + "] " + size + " new commit" + (size > 1 ? "s" : ""), event.compare())
                .setAuthor(event.pusher().getLogin(), event.pusher().getHtmlUrl().toString(), event.pusher().getAvatarUrl())
                .setDescription(description.toString());
    }
}
