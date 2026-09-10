package tabstats.util;

import java.lang.reflect.Field;

/**
 * Minimal stand-in for Forge's {@code ReflectionHelper}, which is unavailable outside Forge.
 *
 * Lunar Client ships Minecraft under MCP <em>named</em> mappings, so the MCP name is the one
 * that resolves at runtime; the SRG name is kept as a fallback for plain Forge. A bytecode
 * remapper never rewrites reflection name strings, so as a last resort fields can also be
 * located by their declared type.
 */
public final class Reflect {
    private Reflect() {
    }

    /** Looks up a field by any of the given names, walking up the class hierarchy. */
    public static Field field(Class<?> owner, String... names) {
        for (Class<?> current = owner; current != null && current != Object.class; current = current.getSuperclass()) {
            for (String name : names) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException | RuntimeException ignored) {
                    // try the next candidate
                }
            }
        }

        return null;
    }

    /**
     * Looks up the only field whose declared type is exactly {@code type}, walking up the class
     * hierarchy. Returns null when there is no such field, or more than one -- an ambiguous match
     * is not worth guessing at.
     */
    public static Field fieldOfType(Class<?> owner, Class<?> type) {
        Field found = null;

        for (Class<?> current = owner; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType() != type) {
                    continue;
                }

                if (found != null) {
                    return null;
                }

                field.setAccessible(true);
                found = field;
            }

            if (found != null) {
                return found;
            }
        }

        return null;
    }

    /** Reads a field, returning null when it cannot be read. */
    @SuppressWarnings("unchecked")
    public static <T> T get(Field field, Object instance) {
        if (field == null) {
            return null;
        }

        try {
            return (T) field.get(instance);
        } catch (IllegalAccessException | RuntimeException ignored) {
            return null;
        }
    }

    /** Writes a field, reporting whether the write happened. */
    public static boolean set(Field field, Object instance, Object value) {
        if (field == null) {
            return false;
        }

        try {
            field.set(instance, value);
            return true;
        } catch (IllegalAccessException | RuntimeException ignored) {
            return false;
        }
    }
}
