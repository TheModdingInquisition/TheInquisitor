package io.github.themoddinginquisition.theinquisitor.commands;

import com.jagrosh.jdautilities.command.SlashCommandEvent;
import io.github.themoddinginquisition.theinquisitor.TheInquisitor;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import java.util.List;

public class ForkCommand extends BaseSlashCommand {

    public ForkCommand() {
        name = "fork";
        help = "Forks a GitHub repository";
        janitorOnly = true;
        options = List.of(new OptionData(OptionType.STRING, "repo", "The repository to fork. Format: 'owner/repo'", true));
    }

    @Override
    protected void exec(SlashCommandEvent event) throws Throwable {
        event.deferReply().queue();
        final var repoOption = resolveRepo(event.getOption("repo", "", OptionMapping::getAsString));
        final var gh = TheInquisitor.getInstance().getGithub();
        final var org = gh.getOrganization(TheInquisitor.getInstance().getConfig().organization);
        final var toFork = gh.getRepository(repoOption);
        final var fork = toFork.forkTo(org);
        event.getHook().sendMessageFormat("Successfully forked repository %s to %s.", toFork.getUrl(), fork.getUrl()).queue();
    }
}
