package io.github.themoddinginquisition.theinquisitor.commands;

import com.jagrosh.jdautilities.command.SlashCommand;
import com.jagrosh.jdautilities.command.SlashCommandEvent;
import io.github.matyrobbrt.curseforgeapi.request.query.ModSearchQuery;
import io.github.matyrobbrt.curseforgeapi.util.Constants;
import io.github.themoddinginquisition.theinquisitor.TheInquisitor;
import io.github.themoddinginquisition.theinquisitor.db.ModsDAO;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.apache.commons.io.IOUtils;
import org.kohsuke.github.GHOrganization;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

public class ForkCommand extends BaseSlashCommand {

    public ForkCommand() {
        name = "fork";
        children = new SlashCommand[] {
                new Create(), new Link()
        };
    }

    public static final class Create extends BaseSlashCommand {
        public Create() {
            name = "create";
            help = "Forks a GitHub repository";
            janitorOnly = true;
            options = List.of(new OptionData(OptionType.STRING, "repo", "The repository to fork. Format: 'owner/repo'", true));
        }

        @Override
        protected void exec(SlashCommandEvent event) throws Throwable {
            event.deferReply().queue();
            final var repoOption = resolveRepo(event.getOption("repo", "", OptionMapping::getAsString));
            final var org = getOrganization();
            final var toFork = getGithub().getRepository(repoOption);
            final var fork = toFork.forkTo(org);
            event.getHook().sendMessageFormat("Successfully forked repository %s to %s.", toFork.getHtmlUrl(), fork.getHtmlUrl()).queue();
            getJanitorsTeam().add(fork, GHOrganization.RepositoryRole.from(GHOrganization.Permission.PUSH));
            final var userAccount = getLinkedAccount(event.getUser().getIdLong());
            if (userAccount != null)
                fork.addCollaborators(GHOrganization.RepositoryRole.from(GHOrganization.Permission.ADMIN), userAccount);
        }
    }

    public static final class Link extends BaseSlashCommand {

        public static final int MODS_CLASS_ID = 6;

        public Link() {
            name = "link";
            help = "Links a fork with a mod.";
            janitorOnly = true;
            options = List.of(
                    new OptionData(OptionType.STRING, "repo", "The name of the fork to link. E.g: Vampirism", true),
                    new OptionData(OptionType.STRING, "mod", "The CurseForge slug of the mod to link the fork to.", true)
            );
        }

        @Override
        protected void exec(SlashCommandEvent event) throws Throwable {
            event.deferReply().queue();
            final var repo = resolveRepo(TheInquisitor.getInstance().getConfig().organization + "/" + event.getOption("repo", "", OptionMapping::getAsString));
            final var parent = getGithub().getRepository(repo).getParent();
            final var modsResponse = TheInquisitor.getInstance().getCurseForgeAPI().getHelper()
                    .searchMods(ModSearchQuery.of(Constants.GameIDs.MINECRAFT)
                            .classId(MODS_CLASS_ID)
                            .slug(event.getOption("mod", "", OptionMapping::getAsString)));
            if (modsResponse.isEmpty()) {
                event.getHook().sendMessage("Could not find mod with that slug!").queue();
                return;
            }
            final var mod = modsResponse.get().get(0);
            final var archivesRepo = getArchivesRepo();
            final var issue = archivesRepo.createIssue(mod.name())
                    .assignee(getLinkedAccount(event.getUser().getIdLong()))
                    .label("in-progress")
                    .body(getIssueBody(parent.getHtmlUrl().toString(), mod.links().websiteUrl()))
                    .create();
            TheInquisitor.getInstance().jdbi().useExtension(ModsDAO.class, db -> db.insert(repo, mod.id(), issue.getNumber()));
            event.getHook()
                    .sendMessage("Linked [%s](%s) to issue %s.".formatted(mod.name(), mod.links().websiteUrl(), issue.getHtmlUrl()))
                    .queue();
        }

        public static String getIssueBody(String repo, String cf) throws IOException {
            return IOUtils.toString(Objects.requireNonNull(TheInquisitor.class.getResource("/issue_template.md")), StandardCharsets.UTF_8)
                    .replace("{repo}", repo)
                    .replace("{curseforge}", cf);
        }
    }
}
