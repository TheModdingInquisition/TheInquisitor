package io.github.themoddinginquisition.theinquisitor.db;

import io.github.themoddinginquisition.theinquisitor.TheInquisitor;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transactional;
import org.jetbrains.annotations.Nullable;

public interface GithubOauthDAO extends Transactional<GithubOauthDAO> {

    /**
     * @deprecated Use the method that inserts an encrypted version of the token.
     */
    @Deprecated
    @SqlUpdate("insert or replace into github_oauth values (:user, :token)")
    void insertPlain(@Bind("user") long userId, @Bind("token") String token);

    default void insertEncrypted(long userId, String token) {
        final var encryptedToken = TheInquisitor.getInstance().getEncryptor().encrypt(token);
        insertPlain(userId, encryptedToken);
    }

    /**
     * @deprecated Use the method that decrypts the token.
     */
    @Deprecated
    @SqlQuery("select token from github_oauth where discord_id = :user")
    String getTokenPlain(@Bind("user") long userId);

    @Nullable
    default String getToken(long userId) {
        final var encrypted = getTokenPlain(userId);
        return encrypted == null ? null : TheInquisitor.getInstance().getEncryptor().decrypt(encrypted);
    }
}
