package tabstats.playerapi;

import java.util.UUID;

/**
 * A player the mod only knows about because they showed up in chat. They have no tab list entry
 * of their own, so {@link tabstats.render.StatsTab} draws an extra row for them.
 */
public final class ChatRevealedPlayer {
    private final UUID uuid;
    private final String name;
    private final boolean realProfile;

    ChatRevealedPlayer(UUID uuid, String name, boolean realProfile) {
        this.uuid = uuid;
        this.name = name;
        this.realProfile = realProfile;
    }

    public UUID getUuid() {
        return this.uuid;
    }

    public String getName() {
        return this.name;
    }

    /**
     * False when no Mojang account exists under this name, i.e. the player is nicked. The UUID is
     * then a locally derived placeholder, so nothing may be looked up with it.
     */
    public boolean hasRealProfile() {
        return this.realProfile;
    }
}
