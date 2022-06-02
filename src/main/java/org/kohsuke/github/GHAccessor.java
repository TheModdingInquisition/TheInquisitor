package org.kohsuke.github;

import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class GHAccessor {
    public static void subscribe(GitHub gitHub, long threadId) throws IOException {
        final var req = gitHub.createRequest()
                .method("PUT")
                .with("ignored", false)
                .withUrlPath("/notifications/threads/" + threadId + "/subscription");
        System.out.println(req.client.sendRequest(req, input -> IOUtils.toString(input.bodyStream())).body());;
    }

    public static GHNotificationStream listNotifications(GitHub github, boolean all, boolean nonBlocking) throws IOException {
        return new GHNotificationStream(github, "/notifications") {
            @Override
            public Iterator<GHThread> iterator() {
                // capture the configuration setting here
                final Requester req = root().createRequest()
                        .with("all", all);

                return new Iterator<GHThread>() {
                    /**
                     * Stuff we've fetched but haven't returned to the caller. Newer ones first.
                     */
                    private GHThread[] threads = EMPTY_ARRAY;

                    /**
                     * Next element in {@link #threads} to return. This counts down.
                     */
                    private int idx = -1;

                    /**
                     * threads whose updated_at is older than this should be ignored.
                     */
                    private long lastUpdated = -1;

                    /**
                     * When is the next polling allowed?
                     */
                    private long nextCheckTime = -1;

                    private GHThread next;

                    public GHThread next() {
                        if (next == null) {
                            next = fetch();
                            if (next == null)
                                throw new NoSuchElementException();
                        }

                        GHThread r = next;
                        next = null;
                        return r;
                    }

                    public boolean hasNext() {
                        if (next == null)
                            next = fetch();
                        return next != null;
                    }

                    GHThread fetch() {
                        try {
                            while (true) {// loop until we get new threads to return

                                // if we have fetched un-returned threads, use them first
                                while (idx >= 0) {
                                    GHThread n = threads[idx--];
                                    long nt = n.getUpdatedAt().getTime();
                                    if (nt >= lastUpdated) {
                                        lastUpdated = nt;
                                        return n;
                                    }
                                }

                                if (nonBlocking && nextCheckTime >= 0)
                                    return null; // nothing more to report, and we aren't blocking

                                // observe the polling interval before making the call
                                while (true) {
                                    long now = System.currentTimeMillis();
                                    if (nextCheckTime < now)
                                        break;
                                    long waitTime = Math.min(Math.max(nextCheckTime - now, 1000), 60 * 1000);
                                    Thread.sleep(waitTime);
                                }

                                Requester requester = req.withUrlPath("/notifications");
                                System.out.println(requester.client.sendRequest(requester, r -> IOUtils.toString(r.bodyStream(), StandardCharsets.UTF_8))
                                        .body());
                                GitHubResponse<GHThread[]> response = ((GitHubPageContentsIterable<GHThread>) requester
                                        .toIterable(GHThread[].class, null)).toResponse();
                                System.out.println(response.body()[0]);
                                threads = response.body();

                                if (threads == null) {
                                    threads = EMPTY_ARRAY; // if unmodified, we get empty array
                                } else {
                                    // we get a new batch, but we want to ignore the ones that we've seen
                                    lastUpdated++;
                                }
                                idx = threads.length - 1;

                                nextCheckTime = calcNextCheckTime(response);
                            }
                        } catch (IOException | InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                    }

                    private long calcNextCheckTime(GitHubResponse<GHThread[]> response) {
                        String v = response.header("X-Poll-Interval");
                        if (v == null)
                            v = "60";
                        long seconds = Integer.parseInt(v);
                        return System.currentTimeMillis() + seconds * 1000;
                    }
                };
            }
        };
    }
    private static final GHThread[] EMPTY_ARRAY = new GHThread[0];
}
