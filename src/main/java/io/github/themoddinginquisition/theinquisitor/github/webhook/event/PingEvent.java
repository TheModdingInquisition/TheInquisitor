package io.github.themoddinginquisition.theinquisitor.github.webhook.event;

import org.jetbrains.annotations.Nullable;
import org.kohsuke.github.GHHook;
import org.kohsuke.github.GHOrganization;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GHUser;

public record PingEvent(String zen, int hook_id, GHHook hook,
                        GHRepository repository,
                        @Nullable GHOrganization organization, GHUser sender) {
}
