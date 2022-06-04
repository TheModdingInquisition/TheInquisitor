package io.github.themoddinginquisition.theinquisitor.db;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.leangen.geantyref.TypeToken;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transactional;
import org.kohsuke.github.GHAccessor;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHLabel;
import org.kohsuke.github.GHPullRequest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Type;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@RegisterRowMapper(PullRequestsDAO.PRData.Mapper.class)
public interface PullRequestsDAO extends Transactional<PullRequestsDAO> {

    Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    Type LONG_LIST_TYPE = new TypeToken<List<Long>>() {}.getType();

    @SqlUpdate("insert or replace into pull_requests(repo, number, thread) values (lower(:repo), :number, :thread)")
    void create(@Bind("repo") String repo, @Bind("number") int prNumber, @Bind("thread") long threadId);

    @SqlUpdate("delete * from pull_requests where thread = :threadId")
    void remove(@Bind("thread") long threadId);

    @SqlUpdate("insert or replace into pull_requests values (lower(:repo), :number, :thread, :comments, :labels, :title, :body, :state, :commits)")
    void update(@Bind("repo") String repo, @Bind("number") int prNumber, @Bind("thread") long threadId,
                @Bind("comments") int comments, @Bind("labels") String labels,
                @Bind("title") String title, @Bind("body") String description,
                @Bind("state") int state, @Bind("commits") int commits);

    default void update(PRData data) {
        update(data.repo(), data.number(), data.threadId(), data.comments(), GSON.toJson(data.labels(), LONG_LIST_TYPE),
                data.title(), data.description(), data.state().ordinal() + 1, data.commits());
    }

    @SqlQuery("select * from pull_requests where thread = :thread")
    PRData getData(@Bind("thread") long threadId);

    @SqlQuery("select * from pull_requests where lower(repo) = lower(:repo) and number = :number")
    PRData getData(@Bind("repo") String repo, @Bind("number") int prNumber);

    @SqlQuery("select * from pull_requests")
    List<PRData> getAll();

    record PRData(String repo, int number, long threadId, int comments, List<Long> labels,
                  String title, String description, PRState state, int commits) {
        public static class Mapper implements RowMapper<PRData> {

            @Override
            public PRData map(ResultSet rs, StatementContext ctx) throws SQLException {
                return new PRData(
                        rs.getString("repo"), rs.getInt("number"),
                        rs.getLong("thread"), rs.getInt("comments"),
                        GSON.fromJson(or(rs.getString("labels"), "[]"), LONG_LIST_TYPE),
                        rs.getString("title"), rs.getString("description"),
                        PRState.getState(rs.getInt("state")), rs.getInt("commits")
                );
            }
        }

        public static PRData from(GHPullRequest pr, long threadId) {
            return new PRData(
                    GHAccessor.getOwner(pr).getFullName(), pr.getNumber(), threadId,
                    pr.getCommentsCount(), pr.getLabels().stream().map(GHLabel::getId).toList(),
                    pr.getTitle(), pr.getBody(), PRState.getState(pr), unwrap(GHAccessor.PR_COMMITS.get(pr))
            );
        }
    }

    enum PRState {
        OPEN, CLOSED, DRAFT, MERGED;

        @Nullable
        public static PRState getState(int id) {
            if (id <= 0)
                return null;
            return values()[id - 1];
        }

        public static PRState getState(GHPullRequest request) {
            if (convert(GHAccessor.PR_IS_MERGED.get(request)))
                return PRState.MERGED;
            else if (GHAccessor.isPRDraft(request) && request.getState() == GHIssueState.OPEN)
                return PRState.DRAFT;
            else if (request.getState() == GHIssueState.CLOSED)
                return PRState.CLOSED;
            else if (request.getState() == GHIssueState.OPEN)
                return PRState.OPEN;
            return PRState.OPEN;
        }

        private static boolean convert(@Nullable Boolean bool) {
            return bool != null && bool;
        }
    }

    private static int unwrap(@Nullable Integer integer) {
        return integer == null ? 0 : integer;
    }

    private static String or(@Nullable String str, @Nonnull String or) {
        return str == null ? or : str;
    }
}
