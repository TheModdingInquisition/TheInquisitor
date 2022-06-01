package io.github.themoddinginquisition.theinquisitor.db;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;

public interface PullRequestsDAO extends Transactional<PullRequestsDAO> {

    @SqlUpdate("insert or replace into pull_requests values (lower(:repo), :number, :thread)")
    void create(@Bind("repo") String repo, @Bind("number") int prNumber, @Bind("thread") long threadId);

    @SqlQuery("select * from pull_requests where thread = :thread")
    PRData getData(@Bind("thread") long threadId);

    record PRData(String repo, int number, long threadId) {
        public static class Mapper implements RowMapper<PRData> {

            @Override
            public PRData map(ResultSet rs, StatementContext ctx) throws SQLException {
                return new PRData(rs.getString(0), rs.getInt(1), rs.getLong(2));
            }
        }
    }
}
