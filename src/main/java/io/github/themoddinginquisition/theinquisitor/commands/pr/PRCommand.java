package io.github.themoddinginquisition.theinquisitor.commands.pr;

import com.jagrosh.jdautilities.command.SlashCommand;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import io.github.themoddinginquisition.theinquisitor.commands.BaseSlashCommand;

public class PRCommand extends BaseSlashCommand {

    public PRCommand(ManagedPRs managedPRs) {
        name = "pr";
        help = "PR related commands.";
        children = new SlashCommand[] {
            new LinkPR(() -> managedPRs)
        };
    }

    @Override
    protected void exec(SlashCommandEvent event) throws Throwable {

    }
}
