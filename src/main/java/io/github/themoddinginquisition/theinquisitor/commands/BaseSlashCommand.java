package io.github.themoddinginquisition.theinquisitor.commands;

import com.jagrosh.jdautilities.command.SlashCommand;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import io.github.themoddinginquisition.theinquisitor.TheInquisitor;
import org.kohsuke.github.GitHub;

import java.util.regex.Pattern;

public abstract class BaseSlashCommand extends SlashCommand {

    protected boolean janitorOnly;

    @Override
    protected final void execute(SlashCommandEvent event) {
        try {
            if (janitorOnly && !isJanitor(event))
                return;
            exec(event);
        } catch (Throwable t) {
            final var cont = "There was an exception executing that command: " + t.getLocalizedMessage();
            if (event.isAcknowledged()) {
                event.getHook().sendMessage(cont).queue();
            }
            event.deferReply(true)
                .setContent(cont)
                .queue();
            TheInquisitor.LOGGER.error("There was an exception executing the \"{}\" command: ", getName(), t);
        }
    }

    protected abstract void exec(SlashCommandEvent event) throws Throwable;

    protected boolean isJanitor(SlashCommandEvent event) {
        final var isJanitor = TheInquisitor.getInstance().getGitHubUserCache().isJanitor(event.getUser().getIdLong());
        if (!isJanitor)
            event.deferReply(true)
                    .setContent("You need to be a Janitor in order to execute that command. If you are one, make sure you `/link` your GitHub and Discord accounts.")
                    .queue();
        return isJanitor;
    }

    public static final Pattern REPO_PATTERN = Pattern.compile("github\\.com/(?<owner>.+)/(?<repo>.+)");

    protected String resolveRepo(String input) {
        final var matcher = REPO_PATTERN.matcher(input);
        if (matcher.find()) {
            return matcher.group(1) + "/" + matcher.group(2);
        } else
            return input;
    }

    protected GitHub getGithub() {
        return TheInquisitor.getInstance().getGithub();
    }
}
