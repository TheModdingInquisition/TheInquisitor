package io.github.themoddinginquisition.theinquisitor.commands.pr;

import io.github.themoddinginquisition.theinquisitor.TheInquisitor;
import io.github.themoddinginquisition.theinquisitor.db.PullRequestsDAO;
import io.github.themoddinginquisition.theinquisitor.util.ThrowingRunnable;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.entities.Emoji;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.ThreadChannel;
import org.apache.commons.lang3.time.StopWatch;
import org.jdbi.v3.core.Jdbi;
import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.GHAccessor;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GitHub;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ManagedPRs implements ThrowingRunnable {

    record ManagedPR(String repo, int number, long threadId) {}

    private final GitHub gitHub;
    private final Supplier<JDA> jda;
    private final Jdbi jdbi;
    private final long channel;
    private final List<ManagedPR> prs = Collections.synchronizedList(new ArrayList<>());

    public ManagedPRs(GitHub gitHub, Jdbi jdbi, Supplier<JDA> jda, long channel) {
        this.gitHub = gitHub;
        this.jdbi = jdbi;
        this.jda = jda;
        this.channel = channel;

        jdbi.useExtension(PullRequestsDAO.class, db -> db.getAll().forEach(data -> prs.add(new ManagedPR(data.repo(), data.number(), data.threadId()))));
    }

    public void manage(String repoName, int number, Consumer<ThreadChannel> channelConsumer) throws IOException {
        final var repo = gitHub.getRepository(repoName);
        final var pr = repo.getPullRequest(number);
        GHAccessor.subscribe(gitHub, pr.getId());
        final var channel = jda.get().getTextChannelById(this.channel);
        if (channel == null)
            throw new NullPointerException("Unknown text channel with ID: " + this.channel);
        final var embed = makePREmbed(pr);

        channel.sendMessage(embed.build())
                .flatMap(msg -> msg.createThreadChannel(repo.getName() + " [" + number + "]"))
                .queue(th -> {
                    prs.add(new ManagedPR(repoName, number, th.getIdLong()));
                    channelConsumer.accept(th);
                    jdbi.useExtension(PullRequestsDAO.class, db -> db.create(repoName, number, th.getIdLong()));
                });
    }

    public static MessageBuilder makePREmbed(GHPullRequest pr) throws IOException {
        final var emoji = getPRStatusEmoji(pr);
        final var emojiMention = emoji == null ? "" : emoji.getAsMention() + " ";
        final var embed = new EmbedBuilder()
                .setAuthor(pr.getUser().getName(), pr.getUser().getUrl().toString(), pr.getUser().getAvatarUrl())
                .setTitle(limit(pr.getTitle(), MessageEmbed.TITLE_MAX_LENGTH), pr.getUrl().toString())
                .setDescription(limit(emojiMention + pr.getBody(), MessageEmbed.DESCRIPTION_MAX_LENGTH))
                .setColor(getPRColour(pr));

        return new MessageBuilder().setEmbeds(embed.build());
    }

    public static EmbedBuilder makeCommentEmbed(GHIssueComment comment) throws IOException {
        final var sender = comment.getUser();
        final var issue = comment.getParent();
        return new EmbedBuilder()
                .setAuthor(sender.getName(), sender.getUrl().toString(), sender.getAvatarUrl())
                .setTitle("New comment on %s #".formatted(issue.isPullRequest() ? "pull request" : "issue"), comment.getUrl().toString())
                .setColor(Color.ORANGE)
                .setDescription(limit(comment.getBody(), MessageEmbed.DESCRIPTION_MAX_LENGTH));
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
    public void run() throws Throwable {
        final var timer = StopWatch.createStarted();

        for (final var it = prs.iterator(); it.hasNext();) {
            final var pr = it.next();

            if (it.hasNext())
                Thread.sleep(100L); // Let's wait before the next request, shall we
        }

        timer.stop();
        TheInquisitor.LOGGER.warn("Checking Pull Request updates took {} seconds", timer.getTime(TimeUnit.SECONDS));
    }
}
