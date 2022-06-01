package org.kohsuke.github;

import java.io.IOException;

public class GHAccessor {
    public static void subscribe(GitHub gitHub, long threadId) throws IOException {
        gitHub.createRequest()
                .method("PUT")
                .withUrlPath("/notifications/threads/" + (int) threadId + "/subscription")
                .send();
    }
}
