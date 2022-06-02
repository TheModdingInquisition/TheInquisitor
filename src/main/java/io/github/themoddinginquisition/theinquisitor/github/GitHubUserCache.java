package io.github.themoddinginquisition.theinquisitor.github;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.themoddinginquisition.theinquisitor.TheInquisitor;
import io.github.themoddinginquisition.theinquisitor.db.GithubOauthDAO;
import io.github.themoddinginquisition.theinquisitor.util.Config;
import org.jdbi.v3.core.Jdbi;
import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHUser;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class GitHubUserCache {
    private final GHOrganization organization;
    private final Jdbi jdbi;
    private final Supplier<Config> config;
    private final Cache<Long, User> idToUser = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();

    public GitHubUserCache(GitHub gitHub, Jdbi jdbi, Supplier<Config> config) throws IOException {
        this.organization = gitHub.getOrganization(config.get().organization);
        this.jdbi = jdbi;
        this.config = config;
    }

    @Nullable
    public User getUser(long id) {
        var user = idToUser.getIfPresent(id);
        if (user == null) {
            final var token = jdbi.withExtension(GithubOauthDAO.class, db -> db.getToken(id));
            if (token == null)
                return null;
            else {
                try {
                    final var myself = new GitHubBuilder()
                            .withJwtToken(token)
                            .build()
                            .getMyself();
                    user = new User(myself.getName(), myself.getLogin(), myself.getAvatarUrl(), myself);
                    idToUser.put(id, user);
                } catch (IOException e) {
                    TheInquisitor.LOGGER.error("Exception trying to resolve linked GitHub account for user with ID {}: ", id, e);
                }
            }
        }
        return user;
    }

    public boolean isJanitor(long userId) {
        try {
            final var team = organization.getTeamByName(config.get().janitorsTeam);
            final var user = getUser(userId);
            if (user != null && team != null)
                return team.hasMember(user.user());
        } catch (IOException e) {
            TheInquisitor.LOGGER.error("Exception trying to determine if {} is a janitor: ", userId, e);
        }
        return false;
    }

    public record User(String name, String login, String avatarUrl, GHUser user) {}
}
