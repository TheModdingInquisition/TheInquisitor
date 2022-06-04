package io.github.themoddinginquisition.theinquisitor.github.webhook.event;

import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;

// https://docs.github.com/en/developers/webhooks-and-events/webhooks/webhook-events-and-payloads#push
public record PushEvent(String ref, String before, String after,
                        boolean created, boolean deleted, boolean forced,
                        GHCommit head_commit, String compare, GHCommit[] commits,
                        GHUser pusher, GHRepository repository, @Nullable GHOrganization organization,
                        @Nullable GHAppInstallation installation, GHUser sender) {
}
