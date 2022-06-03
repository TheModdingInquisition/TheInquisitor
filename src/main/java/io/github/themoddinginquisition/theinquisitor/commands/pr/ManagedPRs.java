package io.github.themoddinginquisition.theinquisitor.commands.pr;

import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import io.github.themoddinginquisition.theinquisitor.TheInquisitor;
import io.github.themoddinginquisition.theinquisitor.db.PullRequestsDAO;
import io.github.themoddinginquisition.theinquisitor.util.ThrowingRunnable;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.entities.Emoji;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.ThreadChannel;
import net.dv8tion.jda.api.requests.RestAction;
import org.apache.commons.collections4.MultiMap;
import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.apache.commons.collections4.multimap.HashSetValuedHashMap;
import org.apache.commons.lang3.time.StopWatch;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.extension.ExtensionConsumer;
import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.GHAccessor;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestCommitDetail;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitUser;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
                    th.retrieveParentMessage().flatMap(Message::pin).queue();
                    prs.add(new ManagedPR(repoName, number, th.getIdLong()));
                    channelConsumer.accept(th);
                    jdbi.useExtension(PullRequestsDAO.class, db -> db.create(repoName, number, th.getIdLong()));
                });
    }

    public static MessageBuilder makePREmbed(GHPullRequest pr) throws IOException {
        final var emoji = getPRStatusEmoji(PullRequestsDAO.PRState.getState(pr));
        final var emojiMention = emoji == null ? "" : emoji.getAsMention() + " ";
        final var embed = new EmbedBuilder()
                .setAuthor(pr.getUser().getName(), pr.getUser().getUrl().toString(), pr.getUser().getAvatarUrl())
                .setTitle(limit(pr.getTitle(), MessageEmbed.TITLE_MAX_LENGTH), pr.getUrl().toString())
                .setDescription(limit(emojiMention + pr.getBody(), MessageEmbed.DESCRIPTION_MAX_LENGTH))
                .setTimestamp(pr.getUpdatedAt().toInstant())
                .setColor(getPRColour(pr));

        return new MessageBuilder().setEmbeds(embed.build());
    }

    public static MessageEmbed makeCommentEmbed(GHIssueComment comment) throws IOException {
        final var sender = comment.getUser();
        final var issue = comment.getParent();
        return new EmbedBuilder()
                .setAuthor(sender.getName(), sender.getUrl().toString(), sender.getAvatarUrl())
                .setTitle("New comment on %s #".formatted(issue.isPullRequest() ? "pull request" : "issue"), comment.getUrl().toString() + issue.getNumber())
                .setColor(Color.ORANGE)
                .setDescription(limit(comment.getBody(), MessageEmbed.DESCRIPTION_MAX_LENGTH))
                .setTimestamp(comment.getCreatedAt().toInstant())
                .build();
    }

    public static List<MessageEmbed> makeCommitEmbeds(GHPullRequest pr, List<GHPullRequestCommitDetail> commitDetails) {
        final var repo = pr.getHead().getRepository(); // TODO maybe try to get the commmits repo from what the detail provides?
        final var byAuthor = new ArrayListValuedHashMap<GHUser, GHCommit>();
        commitDetails.stream()
                .map(GHPullRequestCommitDetail::getSha)
                .map(sha -> {
                    try {
                        return repo.getCommit(sha);
                    } catch (IOException ignored) {}
                    return null;
                })
                .filter(Objects::nonNull)
                .forEach(commit -> {
                    try {
                        final var author = commit.getAuthor();
                        byAuthor.put(author, commit);
                    } catch (IOException ignored) {}
                });
        final var embeds = new ArrayList<MessageEmbed>();
        byAuthor.asMap().forEach((user, commits) -> {
            try {
                final var commitsList = (List<GHCommit>) commits;
                final var name = user.getName();
                final var description = commits.stream()
                        .map(detail -> "[`%s`](%s) %s - %s".formatted(
                                detail.getSHA1().substring(0, 7),
                                detail.getUrl().toString(),
                                limit(getMessage(detail), 50),
                                name
                        ))
                        .reduce("", (a, b) -> a + "\n" + b);
                final var lastCommit = commitsList.get(commitsList.size() - 1);
                embeds.add(new EmbedBuilder()
                                .setAuthor(user.getName(), user.getUrl().toString(), user.getAvatarUrl())
                                .setTitle("[" + pr.getRepository().getName() + "] " + commits.size() + " new commit" + (commits.size() > 1 ? "s" : ""), lastCommit.getUrl().toString())
                                .setDescription(limit(description, MessageEmbed.DESCRIPTION_MAX_LENGTH))
                                .setColor(Color.PINK)
                                .setTimestamp(lastCommit.getCommitDate().toInstant())
                                .build());
            } catch (Exception ignored) {}
        });
        return embeds;
    }

    private static String getMessage(GHCommit commit) {
        try {
            return commit.getCommitShortInfo().getMessage();
        } catch (IOException e) {
            return "Cannot determine commit message.";
        }
    }

    public static MessageEmbed makeStateChangeEmbed(GHPullRequest pr, PullRequestsDAO.PRState oldState, PullRequestsDAO.PRState newState) throws IOException {
        return new EmbedBuilder()
                .setTitle("PR State Change", pr.getUrl().toString())
                .setDescription("""
                        `%s` -> `%s`
                        %s -> %s""".formatted(
                        oldState, newState,
                        getPRStatusEmoji(oldState), getPRStatusEmoji(newState)
                ))
                .setColor(getPRColour(pr))
                .setTimestamp(pr.getUpdatedAt().toInstant())
                .build();
    }

    public static MessageEmbed makeLabelsDiffEmbed(GHPullRequest pr, Stream<GHLabel> added, Stream<GHLabel> removed) {
        final var embed = new EmbedBuilder()
                .setTitle("Labels Updated", pr.getUrl().toString())
                .setColor(Color.LIGHT_GRAY);
        final var addedCombined = String.join(", ", added.map(l -> "`" + l.getName() + "`").toList());
        final var removedCombined = String.join(", ", removed.map(l -> "`" + l.getName() + "`").toList());
        if (!addedCombined.isBlank())
            embed.addField("Added", addedCombined, false);
        if (!removedCombined.isBlank())
            embed.addField("Removed", removedCombined, false);
        return embed.build();
    }

    private static String limit(String str, int limit) {
        if (str == null)
            return "";
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
    public static Emoji getPRStatusEmoji(@Nullable PullRequestsDAO.PRState state) throws IOException {
        if (state == null)
            return null;
        return switch (state) {
            default -> null;
            case OPEN -> PR_OPEN;
        };
    }

    @Override
    public void run() throws Throwable {
        final var timer = StopWatch.createStarted();

        for (final var it = prs.iterator(); it.hasNext();) {
            final var pr = it.next();
            checkPR(pr);
            if (it.hasNext())
                Thread.sleep(100L); // Let's wait before the next request, shall we
        }

        timer.stop();
        TheInquisitor.LOGGER.warn("Checking Pull Request updates took {} seconds", timer.getTime(TimeUnit.SECONDS));
    }

    private void checkPR(ManagedPR managedPR) throws IOException {
        final var pr = resolvePR(managedPR);
        final var oldData = withExtension(db -> db.getData(managedPR.repo(), managedPR.number()));
        final var newData = PullRequestsDAO.PRData.from(pr, managedPR.threadId());
        if (oldData.equals(newData))
            return;

        jdbi.useExtension(PullRequestsDAO.class, db -> db.update(newData));

        final var thread = jda.get().getThreadChannelById(managedPR.threadId());
        if (thread == null) {
            // So.. we lost info..
            jdbi.useExtension(PullRequestsDAO.class, db -> db.remove(managedPR.threadId()));
            prs.remove(managedPR);
            return;
        }

        if (!Objects.equals(newData.title(), oldData.title()) || !Objects.equals(newData.description(), oldData.description())) {
            thread.retrieveParentMessage().queue(msg -> {
                try {
                    msg.editMessage(makePREmbed(pr).build()).queue();
                } catch (IOException e) {
                    TheInquisitor.LOGGER.error("Exception trying to edit old PR embed: ", e);
                }
            });
        }

        if (newData.comments() > oldData.comments()) {
            // So... we have new comments.
            final var comments = pr.listComments().withPageSize(100).toList();
            // We want to start from where we left
            final var newComments = comments.subList(oldData.comments(), comments.size());
            RestAction.allOf(newComments.stream()
                    .map(fun(comment -> thread.sendMessageEmbeds(makeCommentEmbed(comment))))
                    .filter(Objects::nonNull).toList())
                    .queue();
        }

        if (newData.commits() > oldData.commits()) {
            // So... we have new commits.
            final var commits = pr.listCommits().withPageSize(100).toList();
            // We want to start from where we left
            final var newCommits = commits.subList(oldData.commits(), commits.size());
            thread.sendMessageEmbeds(makeCommitEmbeds(pr, newCommits)).queue();
        }

        if (oldData.state() != newData.state()) {
            thread.sendMessageEmbeds(makeStateChangeEmbed(pr, oldData.state(), newData.state())).queue();
        }

        if (!Iterables.elementsEqual(oldData.labels(), newData.labels())) {
            final var repoLabels = pr.getRepository().listLabels()
                    .withPageSize(100)
                    .toList()
                    .stream()
                    .collect(Collectors.toMap(GHLabel::getId, Function.identity()));

            final var added = pr.getLabels().stream()
                    .filter(label -> !oldData.labels().contains(label.getId()));
            final var removed = oldData.labels().stream()
                    .filter(label -> !newData.labels().contains(label))
                    .map(repoLabels::get);

            thread.sendMessageEmbeds(makeLabelsDiffEmbed(pr, added, removed)).queue();
        }
    }

    private static <T, R> Function<T, R> fun(ExtensionCallback<R, T, Exception> cons) {
        return obj -> {
            try {
                return cons.withExtension(obj);
            } catch (Exception e) {
                TheInquisitor.LOGGER.error("Exception: ", e);
                return null;
            }
        };
    }

    private static <T> Consumer<T> cons(ExtensionConsumer<T, Exception> cons) {
        return obj -> {
            try {
                cons.useExtension(obj);
            } catch (Exception e) {
                TheInquisitor.LOGGER.error("Exception: ", e);
            }
        };
    }

    private <T, X extends Exception> T withExtension(ExtensionCallback<T, PullRequestsDAO, X> callback) throws X {
        return jdbi.withExtension(PullRequestsDAO.class, callback);
    }

    private GHPullRequest resolvePR(ManagedPR pr) throws IOException {
        return gitHub.getRepository(pr.repo()).getPullRequest(pr.number());
    }
}
