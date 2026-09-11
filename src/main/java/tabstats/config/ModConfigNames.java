package tabstats.config;

public enum ModConfigNames {
    APIKEY("ApiKey"),
    RENDER_HEADER_FOOTER("RenderHeaderFooter"),
    MOD_ENABLED("ModEnabled"),
    CHAT_REVEAL("ChatReveal"),
    CHAT_REVEAL_DEBUG("ChatRevealDebug"),
    URCHIN_API_KEY("UrchinApiKey");

    private final String name;

    ModConfigNames(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        return name;
    }
}
