package tabstats.config;

import tabstats.TabStats;
import tabstats.util.Handler;
import tabstats.util.Log;
import net.minecraft.client.Minecraft;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.Writer;
import java.util.LinkedHashMap;

import static tabstats.config.ModConfigNames.APIKEY;
import static tabstats.config.ModConfigNames.RENDER_HEADER_FOOTER;
import static tabstats.config.ModConfigNames.STAT_COLUMNS;
import static tabstats.config.ModConfigNames.MOD_ENABLED;
import static tabstats.config.ModConfigNames.URCHIN_API_KEY;

public class ModConfig {
    private static final String CONFIG_FILENAME = "config.json";
    private String apiKey;
    private String urchinApiKey;
    private static ModConfig instance;
    private File configFile;
    private volatile boolean renderHeaderFooter = true;
    private volatile boolean modEnabled = true;
    private final StatColumnLayout statColumns = new StatColumnLayout();
    private long configLastLoaded = -1L;

    public static ModConfig getInstance() {
        if (instance == null) instance = new ModConfig();
        return instance;

    }

    public synchronized String getApiKey() {
        reloadKeysFromDiskIfNeeded();
        return this.apiKey == null ? "" : this.apiKey;
    }

    public synchronized String getUrchinApiKey() {
        reloadKeysFromDiskIfNeeded();
        return this.urchinApiKey == null ? "" : this.urchinApiKey;
    }

    /**
     * A key was changed in config.json by hand. This usually runs on a worker thread - whichever
     * one asked for the key - so the recheck is handed to the client thread, which owns the
     * player cache, the chat reveals and the tab list.
     */
    private void onApiKeyChanged() {
        if (!isModEnabled()) {
            return;
        }

        Handler.onClientThread(() -> {
            TabStats tabStats = TabStats.getTabStats();
            if (tabStats != null && tabStats.getStatWorld() != null) {
                tabStats.getStatWorld().recheckAllPlayers();
            }
        });
    }

    public synchronized void setApiKey(String key) {
        // Only update if the key actually changed
        if (!normalizeKey(key).equals(normalizeKey(this.apiKey))) {
            this.apiKey = key;
        }
    }

    public synchronized void setUrchinApiKey(String key) {
        if (!normalizeKey(key).equals(normalizeKey(this.urchinApiKey))) {
            this.urchinApiKey = key;
        }
    }

    public boolean isRenderHeaderFooterEnabled() {
        return this.renderHeaderFooter;
    }

    public void setRenderHeaderFooterEnabled(boolean value) {
        this.renderHeaderFooter = value;
    }

    public boolean isModEnabled() {
        return this.modEnabled;
    }

    public void setModEnabled(boolean value) {
        this.modEnabled = value;
    }

    /** Which stat columns the tab draws, and in what order. Edited through the /tabstats GUI. */
    public StatColumnLayout getStatColumns() {
        return this.statColumns;
    }

    private void reloadKeysFromDiskIfNeeded() {
        File file = getFile();
        if (!file.exists()) {
            return;
        }

        long modified = file.lastModified();
        if (this.apiKey == null || this.urchinApiKey == null || modified != this.configLastLoaded) {
            JsonObject config = readConfig();
            this.apiKey = keyFromDisk(this.apiKey, getString(config, APIKEY));
            this.urchinApiKey = keyFromDisk(this.urchinApiKey, getString(config, URCHIN_API_KEY));
            this.configLastLoaded = modified;
        }
    }

    /**
     * A key as config.json has it now. One that differs from the key in use was edited by hand,
     * which rechecks everyone; the first read after start has nothing to differ from.
     */
    private String keyFromDisk(String current, String fresh) {
        if (current != null && !normalizeKey(fresh).equals(normalizeKey(current))) {
            onApiKeyChanged();
        }
        return fresh;
    }

    private String normalizeKey(String key) {
        return key == null ? "" : key.trim();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void makeFile() {
        File file = getFile();
        if (file.exists()) {
            return;
        }

        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        try {
            if (file.createNewFile()) {
                JsonObject defaults = new JsonObject();
                defaults.addProperty(MOD_ENABLED.toString(), true);
                defaults.addProperty(RENDER_HEADER_FOOTER.toString(), true);
                defaults.addProperty(APIKEY.toString(), "");
                defaults.addProperty(URCHIN_API_KEY.toString(), "");
                defaults.add(STAT_COLUMNS.toString(), Handler.getGson().toJsonTree(this.statColumns.toSerializable()));

                try (FileWriter writer = new FileWriter(file)) {
                    Handler.getGson().toJson(defaults, writer);
                    writer.flush();
                }
            }
        } catch (Exception ex) {
            Log.warn("Could not create the config file " + file, ex);
        }
    }

    private synchronized void loadConfigFromFile() {
        if (!getFile().exists()) {
            makeFile();
        }

        // One parse for every setting, instead of one per setting
        JsonObject config = readConfig();
        apiKey = getString(config, APIKEY);
        urchinApiKey = getString(config, URCHIN_API_KEY);
        renderHeaderFooter = getBoolean(config, RENDER_HEADER_FOOTER, true);
        modEnabled = getBoolean(config, MOD_ENABLED, true);
        statColumns.loadFrom(getJsonObject(config, STAT_COLUMNS));
        configLastLoaded = getFile().lastModified();
    }

    public File getFile() {
        if (configFile != null) {
            return configFile;
        }

        File folder = null;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.mcDataDir != null) {
                folder = new File(mc.mcDataDir, "tabstats");
            }
        } catch (Throwable ignored) {
            // ignored - we'll fall back to user home .minecraft
        }

        if (folder == null) {
            folder = new File(System.getProperty("user.home") + File.separator + ".minecraft", "tabstats");
        }

        if (!folder.exists()) {
            folder.mkdirs();
        }

        configFile = new File(folder, CONFIG_FILENAME);
        return configFile;
    }

    public void init() {
        loadConfigFromFile();
    }

    public synchronized void save() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put(MOD_ENABLED.toString(), this.modEnabled);
        map.put(RENDER_HEADER_FOOTER.toString(), this.renderHeaderFooter);
        map.put(APIKEY.toString(), this.apiKey == null ? "" : this.apiKey); // Use the internal field, not getApiKey()
        map.put(URCHIN_API_KEY.toString(), this.urchinApiKey == null ? "" : this.urchinApiKey);
        map.put(STAT_COLUMNS.toString(), this.statColumns.toSerializable());
        File file = getFile();
        try (Writer writer = new FileWriter(file)) {
            Handler.getGson().toJson(map, writer);
            writer.flush(); // Ensure it's written to disk
            configLastLoaded = file.lastModified();
        } catch (Exception ex) {
            // Worth a line in the log: an API key that was not written is gone after a restart
            Log.warn("Could not save the config to " + file, ex);
        }
    }

    /** The whole config file, or null when it is missing or unreadable. */
    private JsonObject readConfig() {
        File file = getFile();
        if (!file.exists()) {
            return null;
        }

        try (FileReader reader = new FileReader(file)) {
            JsonElement parsed = new JsonParser().parse(reader);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception ex) {
            Log.warn("Could not read the config from " + file, ex);
            return null;
        }
    }

    private static String getString(JsonObject config, ModConfigNames key) {
        JsonElement element = config == null ? null : config.get(key.toString());
        return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
    }

    private static JsonObject getJsonObject(JsonObject config, ModConfigNames key) {
        JsonElement element = config == null ? null : config.get(key.toString());
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static boolean getBoolean(JsonObject config, ModConfigNames key, boolean defaultValue) {
        JsonElement element = config == null ? null : config.get(key.toString());
        if (element == null || !element.isJsonPrimitive()) {
            return defaultValue;
        }

        try {
            return element.getAsBoolean();
        } catch (RuntimeException ex) {
            return defaultValue;
        }
    }
}
