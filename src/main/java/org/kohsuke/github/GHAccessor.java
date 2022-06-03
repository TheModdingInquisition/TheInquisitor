package org.kohsuke.github;

import org.apache.commons.io.IOUtils;

import javax.annotation.Nullable;
import java.io.IOException;

public class GHAccessor {

    public static final FieldAccessor<GHPullRequest, Boolean> PR_IS_MERGED = getField(GHPullRequest.class, "merged");
    public static final FieldAccessor<GHPullRequest, Integer> PR_COMMITS = getField(GHPullRequest.class, "commits");

    public static void subscribe(GitHub gitHub, long threadId) throws IOException {
        final var req = gitHub.createRequest()
                .method("PUT")
                .with("ignored", false)
                .withUrlPath("/notifications/threads/" + threadId + "/subscription");
        System.out.println(req.client.sendRequest(req, input -> IOUtils.toString(input.bodyStream())).body());;
    }

    public static boolean isPRDraft(GHPullRequest pr) {
        return pr.draft;
    }

    public static GHRepository getOwner(GHIssue issue) {
        return issue.owner;
    }

    @SuppressWarnings("unchecked")
    private static <T, R> FieldAccessor<T, R> getField(Class<T> clazz, String name) {
        try {
            final var field = clazz.getDeclaredField(name);
            field.setAccessible(true);
            return obj -> {
                try {
                    return (R) field.get(obj);
                } catch (IllegalAccessException e) {
                    return null;
                }
            };
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    public interface FieldAccessor<T, R> {
        @Nullable
        R get(@Nullable T object);
    }
}
