package io.github.themoddinginquisition.theinquisitor.db;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transactional;
import org.jetbrains.annotations.Nullable;

public interface ModsDAO extends Transactional<ModsDAO> {
    @SqlUpdate("insert or replace into mods values (lower(:fork), :pid, :issue)")
    void insert(@Bind("fork") String fork, @Bind("pid") int projectId, @Bind("issue") int issue);

    @Nullable
    @SqlQuery("select issue from mods where fork = lower(:fork)")
    Integer getIssue(@Bind("fork") String fork);
}
