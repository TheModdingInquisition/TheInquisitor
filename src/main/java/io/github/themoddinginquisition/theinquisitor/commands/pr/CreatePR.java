package io.github.themoddinginquisition.theinquisitor.commands.pr;

import com.jagrosh.jdautilities.command.SlashCommandEvent;
import com.matyrobbrt.jdahelper.DismissListener;
import io.github.themoddinginquisition.theinquisitor.TheInquisitor;
import io.github.themoddinginquisition.theinquisitor.commands.BaseSlashCommand;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import java.util.List;
import java.util.function.Supplier;

class CreatePR extends BaseSlashCommand {

    private final Supplier<ManagedPRs> manager;

    public CreatePR(Supplier<ManagedPRs> manager) {
        this.manager = manager;

        name = "create";
        help = "Creates a PR";
        janitorOnly = true;
        options = List.of(
                new OptionData(OptionType.STRING, "repo", "The target repository", true),
                new OptionData(OptionType.STRING, "base", "The PR base branch", true),
                new OptionData(OptionType.STRING, "target", "The PR head branch", true),
                new OptionData(OptionType.STRING, "title", "The PR title", true),
                new OptionData(OptionType.STRING, "description", "The PR description", false),
                new OptionData(OptionType.BOOLEAN, "draft", "If the PR should be draft", false)
        );
    }

    @Override
    protected void exec(SlashCommandEvent event) throws Throwable {
        event.deferReply().queue();
        final var repo = resolveRepo(event.getOption("repo", "", OptionMapping::getAsString));
        final var base = event.getOption("base", "", OptionMapping::getAsString);
        final var head = event.getOption("target", "", OptionMapping::getAsString);
        final var title = event.getOption("title", "", OptionMapping::getAsString);
        var description = event.getOption("description", "Description pending..", OptionMapping::getAsString);

        description = description + "\n*Sponsored by [The Modding Inquisition](https://github.com/TheModdingInquisition)";

        final var targetRepo = getGithub().getRepository(repo);
        final var pr = targetRepo.createPullRequest(
                title, head,
                TheInquisitor.getInstance().getConfig().organization + "/" + base,
                description, true,
                event.getOption("draft", false, OptionMapping::getAsBoolean)
        );
        manager.get().manage(targetRepo.getFullName(), pr.getNumber(), thread -> {
            event.getHook().sendMessage("""
                            Created [PR](%s) to [%s](%s).
                            Pull request linked to %s""".formatted(pr.getUrl(), targetRepo.getFullName(), targetRepo.getUrl(), thread.getAsMention()))
                    .queue();
        });
    }
}
