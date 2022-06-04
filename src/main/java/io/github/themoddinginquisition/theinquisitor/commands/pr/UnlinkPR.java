package io.github.themoddinginquisition.theinquisitor.commands.pr;

import com.jagrosh.jdautilities.command.SlashCommandEvent;
import com.matyrobbrt.jdahelper.components.Component;
import io.github.themoddinginquisition.theinquisitor.commands.BaseSlashCommand;
import net.dv8tion.jda.api.entities.ThreadChannel;
import net.dv8tion.jda.api.interactions.components.buttons.ButtonStyle;

import java.util.List;
import java.util.function.Supplier;

class UnlinkPR extends BaseSlashCommand {

    private final Supplier<ManagedPRs> manager;

    public UnlinkPR(Supplier<ManagedPRs> manager) {
        this.manager = manager;
        name = "unlink";
        help = "Unlinks a PR from Discord";
        janitorOnly = true;
    }

    @Override
    protected void exec(SlashCommandEvent event) {
        if (event.getChannel() instanceof ThreadChannel thread) {
            final var data = manager.get().remove(thread.getIdLong());
            if (data != null) {
                event.deferReply().setContent("The PR in this channel has been unlinked.")
                        .addActionRow(manager.get().components.createButton(ButtonStyle.PRIMARY, Component.Lifespan.PERMANENT, List.of(
                                        data.repo(), String.valueOf(data.number())
                                ), ManagedPRs.ButtonType.RELINK)
                                .label("Relink")
                                .build())
                        .flatMap($ -> thread.getManager().setArchived(false))
                        .queue();
            }
        } else
            event.deferReply(true).setContent("You cannot use this command in this channel.");
    }
}
