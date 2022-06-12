package io.github.themoddinginquisition.theinquisitor.commands.pr;

import com.jagrosh.jdautilities.command.SlashCommandEvent;
import io.github.themoddinginquisition.theinquisitor.TheInquisitor;
import io.github.themoddinginquisition.theinquisitor.commands.BaseSlashCommand;
import io.github.themoddinginquisition.theinquisitor.db.ModsDAO;
import io.github.themoddinginquisition.theinquisitor.util.Utils;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

class CreatePR extends BaseSlashCommand {

    public static final Pattern PRS_PATTERN = Pattern.compile("<prs>([\\s\\S]*)</prs>");

    private final Supplier<ManagedPRs> manager;

    public CreatePR(Supplier<ManagedPRs> manager) {
        this.manager = manager;

        name = "create";
        help = "Creates a PR";
        janitorOnly = true;
        options = List.of(
                new OptionData(OptionType.STRING, "repo", "The target repository", true),
                new OptionData(OptionType.STRING, "base", "The target branch of the PR", true),
                new OptionData(OptionType.STRING, "head", "The PR source branch", true),
                new OptionData(OptionType.STRING, "title", "The PR title", true),
                new OptionData(OptionType.STRING, "description", "The PR description", false),
                new OptionData(OptionType.BOOLEAN, "draft", "If the PR should be draft", false)
        );
    }

    @Override
    protected void exec(SlashCommandEvent event) throws Throwable {
        event.deferReply().queue();
        final var repo = resolveRepo(event.getOption("repo", "", OptionMapping::getAsString));
        final var base =  event.getOption("base", "", OptionMapping::getAsString);
        final var head = event.getOption("head", "", OptionMapping::getAsString);
        final var title = event.getOption("title", "", OptionMapping::getAsString);
        var description = Utils.getText(event.getOption("description", "Description pending..", OptionMapping::getAsString));

        description = description + "\n\n*Sponsored by [The Modding Inquisition](https://github.com/TheModdingInquisition)*";

        final var orgName = getConfig().organization;
        final var targetRepo = getGithub().getRepository(repo);
        final var archivesIssue = new AtomicReference<Integer>();
        // So, first, let's try to see if there's a fork with the same name
        TheInquisitor.getInstance().jdbi().useExtension(ModsDAO.class, db -> archivesIssue.set(db.getIssue(orgName + "/" + targetRepo.getName())));
        if (archivesIssue.get() == null) {
            // In this case, the fork has a different name
            targetRepo.listForks()
                    .withPageSize(100)
                    .toList()
                    .stream()
                    .filter(rp -> rp.getOwnerName().equalsIgnoreCase(TheInquisitor.getInstance().getConfig().organization))
                    .findFirst()
                    .ifPresent(fork -> TheInquisitor.getInstance().jdbi().useExtension(ModsDAO.class, db -> archivesIssue.set(db.getIssue(fork.getFullName()))));
        }
        final var pr = targetRepo.createPullRequest(
                title, TheInquisitor.getInstance().getConfig().organization + ":" + head,
                base, description, true,
                event.getOption("draft", false, OptionMapping::getAsBoolean)
        );
        manager.get().manage(targetRepo.getFullName(), pr.getNumber(), thread -> {
            event.getHook().sendMessage("""
                            Created [PR](%s) to [%s](%s).
                            Pull request linked to %s""".formatted(pr.getUrl(), targetRepo.getFullName(), targetRepo.getUrl(), thread.getAsMention()))
                    .queue();
        });
        if (archivesIssue.get() != null) {
            final var issue = getArchivesRepo().getIssue(archivesIssue.get());
            var body = issue.getBody();
            body = PRS_PATTERN.matcher(body).replaceFirst(result -> {
                final var group = result.group(1);
                return """
                    <prs>%s
                    - %s
                    </prs>"""
                        .formatted(group.substring(0, group.lastIndexOf(System.lineSeparator())), pr.getHtmlUrl());
            });
            issue.setBody(body);

            final var inquisitor = getLinkedAccount(event.getUser().getIdLong());
            if (inquisitor != null && issue.getAssignees().size() < 10 && issue.getAssignees().stream().noneMatch(user -> user.equals(inquisitor))) {
                issue.addAssignees(inquisitor);
            }
        }
    }
}
