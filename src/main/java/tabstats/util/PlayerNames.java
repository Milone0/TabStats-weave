package tabstats.util;

import java.util.regex.Pattern;

/** What counts as a Minecraft player name, for everything that has to tell one from other text. */
public final class PlayerNames {
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private PlayerNames() {
    }

    public static boolean isValid(String name) {
        return name != null && VALID.matcher(name).matches();
    }
}
