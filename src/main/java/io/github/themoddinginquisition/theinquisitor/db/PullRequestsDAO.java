package io.github.themoddinginquisition.theinquisitor.db;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.leangen.geantyref.TypeToken;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transactional;

import javax.annotation.Nullable;
import java.lang.reflect.Type;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@RegisterRowMapper(PullRequestsDAO.PRData.Mapper.class)
public interface PullRequestsDAO extends Transactional<PullRequestsDAO> {

    Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    Type STRING_LIST_TYPE = new TypeToken<List<String>>() {}.getType();

    @SqlUpdate("insert or replace into pull_requests values (lower(:repo), :number, :thread)")
    void create(@Bind("repo") String repo, @Bind("number") int prNumber, @Bind("thread") long threadId);

    @SqlQuery("select * from pull_requests where thread = :thread")
    PRData getData(@Bind("thread") long threadId);

    @SqlQuery("select * from pull_requests")
    List<PRData> getAll();

    record PRData(String repo, int number, long threadId, long lastComment, List<String> labels,
                  String title, String description, PRState state, String lastCommit) {
        public static class Mapper implements RowMapper<PRData> {

            @Override
            public PRData map(ResultSet rs, StatementContext ctx) throws SQLException {
                return new PRData(
                        rs.getString("repo"), rs.getInt("number"),
                        rs.getLong("thread"), rs.getLong("last_comment"),
                        GSON.fromJson(rs.getString("labels"), STRING_LIST_TYPE),
                        rs.getString("title"), rs.getString("description"),
                        PRState.getState(rs.getInt("state")), rs.getString("last_commit")
                );
            }
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
    }
}
