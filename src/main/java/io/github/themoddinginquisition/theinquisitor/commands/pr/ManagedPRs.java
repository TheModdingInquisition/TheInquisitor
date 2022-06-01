package io.github.themoddinginquisition.theinquisitor.commands.pr;

import io.github.themoddinginquisition.theinquisitor.db.PullRequestsDAO;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Emoji;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.ThreadChannel;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.EventListener;
import org.jdbi.v3.core.Jdbi;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.GHAccessor;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GitHub;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ManagedPRs implements EventListener {

    private static final String UPDATE_TITLE_MODAL = "update_pr_title";
    private static final String UPDATE_DESCRIPTION_MODAL = "update_pr_description";

    private final GitHub gitHub;
    private final Supplier<JDA> jda;
    private final Jdbi jdbi;
    private final long channel;
    private final List<PullRequestsDAO.PRData> prs = Collections.synchronizedList(new ArrayList<>());

    public ManagedPRs(GitHub gitHub, Jdbi jdbi, Supplier<JDA> jda, long channel) {
        this.gitHub = gitHub;
        this.jdbi = jdbi;
        this.jda = jda;
        this.channel = channel;
    }

    public void manage(String repoName, int number, Consumer<ThreadChannel> channelConsumer) throws IOException {
        final var repo = gitHub.getRepository(repoName);
        final var pr = repo.getPullRequest(number);
        GHAccessor.subscribe(gitHub, pr.getId());
        final var channel = jda.get().getTextChannelById(this.channel);
        if (channel == null)
            throw new NullPointerException("Unknown text channel with ID: " + this.channel);
        final var embed = makePREmbed(pr);

        channel.sendMessageEmbeds(embed.build())
                .flatMap(msg -> msg.createThreadChannel(repo.getName() + " [" + number + "]"))
                .queue(th -> {
                    prs.add(new PullRequestsDAO.PRData(repoName, number, th.getIdLong()));
                    channelConsumer.accept(th);
                    jdbi.useExtension(PullRequestsDAO.class, db -> db.create(repoName, number, th.getIdLong()));
                });
    }

    public static EmbedBuilder makePREmbed(GHPullRequest pr) throws IOException {
        final var emoji = getPRStatusEmoji(pr);
        final var emojiMention = emoji == null ? "" : emoji.getAsMention() + " ";
        return new EmbedBuilder()
                .setAuthor(pr.getUser().getName(), pr.getUser().getUrl().toString(), pr.getUser().getAvatarUrl())
                .setTitle(limit(pr.getTitle(), MessageEmbed.TITLE_MAX_LENGTH), pr.getUrl().toString())
                .setDescription(limit(emojiMention + pr.getBody(), MessageEmbed.DESCRIPTION_MAX_LENGTH))
                .setColor(getPRColour(pr));
    }

    private static String limit(String str, int limit) {
        return str.length() > limit ? str.substring(0, limit - 3) + "..." : str;
    }

    public static int getPRColour(GHPullRequest pr) throws IOException {
        if (pr.isDraft())
            return 0x8b949e;
        else if (pr.isMerged())
            return 0xa371f7;
        else return switch (pr.getState()) {
            case ALL -> 0xffffff;
            case CLOSED -> 0xf85149;
            case OPEN -> 0x3fb950;
        };
    }

    public static final Emoji PR_OPEN = Emoji.fromEmote("propen", 981645554473914439L, false);

    // TODO do it for all states using the Nitwit emotes
    @Nullable
    public static Emoji getPRStatusEmoji(GHPullRequest pr) throws IOException {
        return switch (pr.getState()) {
            default -> null;
            case OPEN -> PR_OPEN;
        };
    }

    @Override
    public void onEvent(@NotNull GenericEvent event) {
        if (event instanceof ButtonInteractionEvent buttonEvent)
            onButton(buttonEvent);
        else if (event instanceof ModalInteractionEvent modalEvent)
            onModal(modalEvent);
    }

    private void onButton(ButtonInteractionEvent event) {
        if (event.getButton().getId() == null)
            return;

        // TODO
        switch (event.getButton().getId()) {

        }
    }

    private void onModal(ModalInteractionEvent event) {

    }
}
