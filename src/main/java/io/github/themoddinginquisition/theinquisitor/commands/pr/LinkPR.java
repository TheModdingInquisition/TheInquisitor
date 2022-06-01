package io.github.themoddinginquisition.theinquisitor.commands.pr;

import com.jagrosh.jdautilities.command.SlashCommandEvent;
import io.github.themoddinginquisition.theinquisitor.commands.BaseSlashCommand;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import java.util.List;
import java.util.function.Supplier;

class LinkPR extends BaseSlashCommand {

    private final Supplier<ManagedPRs> manager;

    public LinkPR(Supplier<ManagedPRs> manager) {
        this.manager = manager;

        name = "link";
        help = "Links a PR to Discord";
        janitorOnly = true;
        options = List.of(
            new OptionData(OptionType.STRING, "repo", "The repository of the PR"),
            new OptionData(OptionType.INTEGER, "id", "The PR id")
        );
    }

    @Override
    protected void exec(SlashCommandEvent event) throws Throwable {
        final var repo = resolveRepo(event.getOption("repo", "", OptionMapping::getAsString));
        final var id = event.getOption("id", 0, OptionMapping::getAsInt);
        event.deferReply().queue();
        manager.get().manage(repo, id, thread -> event.getHook().sendMessage("Linked PR with thread " + thread.getAsMention())
                .queue());
    }
}
