package com.cloudcore.client.surface;

/** Strict revision ordering for {@code ELEMENT_UPDATE}. */
public final class UpdateRevision {

    public enum Decision {
        APPLY,
        STALE,
        GAP
    }

    private UpdateRevision() {
    }

    public static Decision decide(int current, int incoming) {
        if (incoming <= current) {
            return Decision.STALE;
        }
        if (incoming != current + 1) {
            return Decision.GAP;
        }
        return Decision.APPLY;
    }
}
