package io.github.themoddinginquisition.theinquisitor.commands.pr;

import com.google.common.collect.Iterables;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.matyrobbrt.jdahelper.components.Component;
import com.matyrobbrt.jdahelper.components.ComponentListener;
import com.matyrobbrt.jdahelper.components.context.ButtonInteractionContext;
import com.matyrobbrt.jdahelper.components.context.ModalInteractionContext;
import io.github.themoddinginquisition.theinquisitor.TheInquisitor;
import io.github.themoddinginquisition.theinquisitor.db.PullRequestsDAO;
import io.github.themoddinginquisition.theinquisitor.util.ThrowingRunnable;
import io.github.themoddinginquisition.theinquisitor.util.Utils;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.entities.Emoji;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.ThreadChannel;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.requests.RestAction;
import org.apache.commons.collections4.multimap.ArrayListValuedHashMap;
import org.apache.commons.lang3.time.StopWatch;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.GHAccessor;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestCommitDetail;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;

import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ManagedPRs implements ThrowingRunnable {

    record ManagedPR(String repo, int number, long threadId) {
    }

    private final GitHub gitHub;
    private final Supplier<JDA> jda;
    private final Jdbi jdbi;
    private final long channel;
    private final List<ManagedPR> prs = Collections.synchronizedList(new ArrayList<>());
    final ComponentListener components;

    public ManagedPRs(GitHub gitHub, Jdbi jdbi, Supplier<JDA> jda, long channel) {
        this.gitHub = gitHub;
        this.jdbi = jdbi;
        this.jda = jda;
        this.channel = channel;

        jdbi.useExtension(PullRequestsDAO.class, db -> db.getAll().forEach(data -> prs.add(new ManagedPR(data.repo(), data.number(), data.threadId()))));
        this.components = TheInquisitor.getComponentListener("pr-manager")
                .onButtonInteraction(this::onButtonInteraction)
                .onModalInteraction(ctx -> {
                    try {
                        this.onModalInteraction(ctx);
                    } catch (Exception e) {
                        ctx.deferReply(true)
                                .setContent("There was an exception handling that modal: " + e.getLocalizedMessage())
                                .queue();
                        TheInquisitor.LOGGER.error("Exception handling modal interaction: ", e);
                    }
                })
                .build();
    }

    public void manage(String repoName, int number, Consumer<ThreadChannel> channelConsumer) throws IOException {
        final var repo = gitHub.getRepository(repoName);
        final var pr = repo.getPullRequest(number);
        final var channel = jda.get().getTextChannelById(this.channel);
        if (channel == null)
            throw new NullPointerException("Unknown text channel with ID: " + this.channel);
        final var embed = makePREmbed(pr);

        channel.sendMessage(embed.build())
                .flatMap(msg -> msg.createThreadChannel(repo.getName() + " [" + number + "]"))
                .queue(th -> {
                    th.retrieveParentMessage().flatMap(msg -> th.pinMessageById(msg.getIdLong())).queue();
                    prs.add(new ManagedPR(repoName, number, th.getIdLong()));
                    channelConsumer.accept(th);
                    jdbi.useExtension(PullRequestsDAO.class, db -> db.update(PullRequestsDAO.PRData.from(pr, th.getIdLong())));
                });
    }

    @Nullable
    @CanIgnoreReturnValue
    public ManagedPR remove(long threadId) {
        final var old = prs.stream().filter(pr -> pr.threadId() == threadId)
                .findFirst();
        if (old.isPresent()) {
            prs.remove(old.get());
            return old.get();
        }
        return null;
    }

    public MessageBuilder makePREmbed(GHPullRequest pr) throws IOException {
        final var emoji = getPRStatusEmoji(PullRequestsDAO.PRState.getState(pr));
        final var emojiMention = emoji == null ? "" : emoji.getAsMention() + " ";
        final var embed = new EmbedBuilder()
                .setAuthor(pr.getUser().getName(), pr.getUser().getHtmlUrl().toString(), pr.getUser().getAvatarUrl())
                .setTitle(limit(pr.getTitle(), MessageEmbed.TITLE_MAX_LENGTH), pr.getHtmlUrl().toString())
                .setDescription(limit(emojiMention + pr.getBody(), MessageEmbed.DESCRIPTION_MAX_LENGTH))
                .setTimestamp(pr.getUpdatedAt().toInstant())
                .setColor(getPRColour(pr))
                .build();

        final var msg = new MessageBuilder().setEmbeds(embed);

        if (gitHub.getMyself().getId() == pr.getUser().getId()) {
            // We did the thing, let's add the buttons
            final var args = List.of(
                    pr.getRepository().getFullName(),
                    String.valueOf(pr.getNumber())
            );
            msg.setActionRows(ActionRow.of(
                    components.createButton(ButtonStyle.SECONDARY, Component.Lifespan.PERMANENT, args, ButtonType.EDIT_TITLE)
                            .label("Edit Title")
                            .build(),
                    components.createButton(ButtonStyle.SECONDARY, Component.Lifespan.PERMANENT, args, ButtonType.EDIT_DESCRIPTION)
                            .label("Edit Description")
                            .build()
            ));
        }

        return msg;
    }

    public static MessageEmbed makeCommentEmbed(GHIssueComment comment) throws IOException {
        final var sender = comment.getUser();
        final var issue = comment.getParent();
        return new EmbedBuilder()
                .setAuthor(sender.getName(), sender.getHtmlUrl().toString(), sender.getAvatarUrl())
                .setTitle("New comment on %s #".formatted(issue.isPullRequest() ? "pull request" : "issue") + issue.getNumber(), comment.getHtmlUrl().toString())
                .setColor(Color.ORANGE)
                .setDescription(limit(comment.getBody(), MessageEmbed.DESCRIPTION_MAX_LENGTH))
                .setTimestamp(comment.getCreatedAt().toInstant())
                .build();
    }

    public static List<MessageEmbed> makeCommitEmbeds(GHPullRequest pr, List<GHPullRequestCommitDetail> commitDetails) {
        final var repo = pr.getHead().getRepository(); // TODO maybe try to get the commits repo from what the detail provides?
        final var byAuthor = new ArrayListValuedHashMap<GHUser, GHCommit>();
        commitDetails.stream()
                .map(GHPullRequestCommitDetail::getSha)
                .map(sha -> {
                    try {
                        return repo.getCommit(sha);
                    } catch (IOException ignored) {
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .forEach(commit -> {
                    try {
                        final var author = commit.getAuthor();
                        byAuthor.put(author, commit);
                    } catch (IOException ignored) {
                    }
                });
        final var embeds = new ArrayList<MessageEmbed>();
        byAuthor.asMap().forEach((user, commits) -> {
            try {
                final var commitsList = (List<GHCommit>) commits;
                final var name = user.getName();
                final var description = commits.stream()
                        .map(detail -> "[`%s`](%s) %s - %s".formatted(
                                detail.getSHA1().substring(0, 7),
                                detail.getHtmlUrl().toString(),
                                limit(getMessage(detail), 50),
                                name
                        ))
                        .reduce("", (a, b) -> a + "\n" + b);
                final var lastCommit = commitsList.get(commitsList.size() - 1);
                embeds.add(new EmbedBuilder()
                        .setAuthor(user.getName(), user.getHtmlUrl().toString(), user.getAvatarUrl())
                        .setTitle("[" + pr.getRepository().getName() + "] " + commits.size() + " new commit" + (commits.size() > 1 ? "s" : ""), lastCommit.getHtmlUrl().toString())
                        .setDescription(limit(description, MessageEmbed.DESCRIPTION_MAX_LENGTH))
                        .setColor(Color.PINK)
                        .setTimestamp(lastCommit.getCommitDate().toInstant())
                        .build());
            } catch (Exception ignored) {
            }
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
                .setTitle("PR State Change", pr.getHtmlUrl().toString())
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
                .setTitle("Labels Updated", pr.getHtmlUrl().toString())
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

    public static int getPRColour(GHPullRequest pr) {
        return switch (PullRequestsDAO.PRState.getState(pr)) {
            case CLOSED -> 0xf85149;
            case OPEN -> 0x3fb950;
            case DRAFT -> 0x8b949e;
            case MERGED -> 0xa371f7;
        };
    }

    public static final Emoji PR_OPEN = Emoji.fromEmote("propen", 866107061523447878L, false);
    public static final Emoji PR_DRAFT = Emoji.fromEmote("prdraft", 866107061145829376L, false);
    public static final Emoji PR_MERGED = Emoji.fromEmote("prmerged", 866107061354889266L, false);
    public static final Emoji PR_CLOSED = Emoji.fromEmote("prclosed", 866107061058011136L, false);

    @Nullable
    public static Emoji getPRStatusEmoji(@Nullable PullRequestsDAO.PRState state) {
        if (state == null)
            return null;
        return switch (state) {
            case CLOSED -> PR_CLOSED;
            case OPEN -> PR_OPEN;
            case DRAFT -> PR_DRAFT;
            case MERGED -> PR_MERGED;
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
        if (timer.getTime(TimeUnit.SECONDS) > 3)
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
            // So... we lost the thread
            jdbi.useExtension(PullRequestsDAO.class, db -> db.remove(managedPR.threadId()));
            prs.remove(managedPR);
            return;
        }

        if (!Objects.equals(newData.title(), oldData.title()) || !Objects.equals(newData.description(), oldData.description())) {
            updateParentMessage(pr, thread);
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
            updateParentMessage(pr, thread);
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

        if (newData.state() == PullRequestsDAO.PRState.MERGED && oldData.state() != PullRequestsDAO.PRState.MERGED) {
            prs.remove(managedPR);
            jdbi.useExtension(PullRequestsDAO.class, db -> db.remove(thread.getIdLong()));
            thread.sendMessage("""
                                    The PR linked with this thread has been merged, and as such, this thread will be archived and the link will be removed.
                                    Note: the PR can always be re-linked using the button attached to this message.""")
                    .setActionRow(components.createButton(ButtonStyle.PRIMARY, Component.Lifespan.PERMANENT, List.of(
                                    pr.getRepository().getFullName(), String.valueOf(pr.getNumber())
                            ), ButtonType.RELINK)
                            .label("Relink")
                            .build())
                    .flatMap($ -> thread.getManager().setArchived(true))
                    .queue();
        }
    }

    private void updateParentMessage(GHPullRequest pr, ThreadChannel thread) {
        thread.retrieveParentMessage().queue(msg -> {
            try {
                msg.editMessage(makePREmbed(pr).build()).queue();
            } catch (IOException e) {
                TheInquisitor.LOGGER.error("Exception trying to edit old PR embed: ", e);
            }
        });
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

    private <T, X extends Exception> T withExtension(ExtensionCallback<T, PullRequestsDAO, X> callback) throws X {
        return jdbi.withExtension(PullRequestsDAO.class, callback);
    }

    private GHPullRequest resolvePR(ManagedPR pr) throws IOException {
        return gitHub.getRepository(pr.repo()).getPullRequest(pr.number());
    }

    private void onButtonInteraction(ButtonInteractionContext context) {
        final var buttonType = ButtonType.valueOf(context.getItemComponentArguments().get(0));

        switch (buttonType) {
            case RELINK -> {
                if (!(context.getChannel() instanceof ThreadChannel thread)) return;
                final var pr = context.getArguments().get(0).toLowerCase(Locale.ROOT);
                final int number = context.getArgument(1, () -> 0, Integer::parseInt);
                prs.add(new ManagedPR(pr, number, thread.getIdLong()));
                final var data = new AtomicReference<PullRequestsDAO.PRData>();
                try {
                    data.set(PullRequestsDAO.PRData.from(gitHub.getRepository(pr).getPullRequest(number), thread.getIdLong()));
                } catch (IOException ignored) {}
                jdbi.useExtension(PullRequestsDAO.class, db -> {
                    if (data.get() == null) {
                        db.create(pr, number, thread.getIdLong());
                    } else {
                        db.update(data.get());
                    }
                });
                thread.getManager()
                        .setArchived(false)
                        .flatMap($ -> thread.sendMessage("This thread has been relinked with https://github.com/" + pr))
                        .queue();
            }
            case EDIT_TITLE -> {
                final var repo = context.getArguments().get(0);
                final var num = context.getArgument(1, () -> 0, Integer::parseInt);
                final var oldTitle = withExtension(db -> db.getData(repo, num));
                final var modal = components.createModal("Update PR title", Component.Lifespan.TEMPORARY, context.getArguments(), "edit_title")
                        .addActionRow(TextInput.create("title", "New Title", TextInputStyle.PARAGRAPH)
                                .setRequired(true)
                                .setPlaceholder("Title")
                                .setValue(oldTitle == null ? null : limit(oldTitle.title(), TextInput.MAX_VALUE_LENGTH))
                                .build())
                        .build();
                context.getEvent().replyModal(modal).queue();
            }
            case EDIT_DESCRIPTION -> {
                final var repo = context.getArguments().get(0);
                final var num = context.getArgument(1, () -> 0, Integer::parseInt);
                final var oldDescription = withExtension(db -> db.getData(repo, num));
                final var modal = components.createModal("Update PR description", Component.Lifespan.TEMPORARY, context.getArguments(), "edit_description")
                        .addActionRow(TextInput.create("description", "New Description", TextInputStyle.PARAGRAPH)
                                .setRequired(true)
                                .setPlaceholder("Description")
                                .setValue(oldDescription == null ? null : limit(oldDescription.description(), TextInput.MAX_PLACEHOLDER_LENGTH))
                                .build())
                        .build();
                context.getEvent().replyModal(modal).queue();
            }
        }
    }

    private void onModalInteraction(ModalInteractionContext context) throws IOException {
        switch (context.getItemComponentArguments().get(0)) {
            case "edit_description" -> {
                final var pr = gitHub.getRepository(context.getArguments().get(0))
                        .getPullRequest(context.getArgument(1, () -> 0, Integer::parseInt));
                final var newText = Utils.getText(Objects.requireNonNull(context.getEvent().getValue("description")).getAsString())
                        + "\n\n*Sponsored by [The Modding Inquisition](https://github.com/TheModdingInquisition)*";
                pr.setBody(newText);
                context.deferReply(true).setContent("Successfully updated PR description!").queue();
            }
            case "edit_title" -> {
                final var pr = gitHub.getRepository(context.getArguments().get(0))
                        .getPullRequest(context.getArgument(1, () -> 0, Integer::parseInt));
                final var newLabel = Objects.requireNonNull(context.getEvent().getValue("label")).getAsString();
                pr.setTitle(newLabel);
                context.deferReply(true).setContent("Successfully updated PR title!").queue();
            }
        }
    }

    enum ButtonType {
        RELINK,
        EDIT_DESCRIPTION,
        EDIT_TITLE
    }
}
