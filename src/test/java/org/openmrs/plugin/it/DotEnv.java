package org.openmrs.plugin.it;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal .env file loader.
 *
 * Supports:
 *   KEY=value
 *   KEY="value"   (double-quoted)
 *   KEY='value'   (single-quoted)
 *   # comment lines
 *   blank lines
 *
 * get() checks System.getenv() first, so real environment variables always
 * take precedence over .env file values.
 */
public class DotEnv {

    /**
     * Loads a .env file. Returns an empty map if the file does not exist.
     */
    public static Map<String, String> load(Path path) throws IOException {
        if (!Files.exists(path)) {
            return Collections.emptyMap();
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(path)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 1) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            value = stripQuotes(value);
            values.put(key, value);
        }
        return values;
    }

    /**
     * Returns the value for key, preferring a real environment variable over
     * a .env file value. Returns null if not found in either.
     */
    public static String get(Map<String, String> dotEnv, String key) {
        String envVal = System.getenv(key);
        return envVal != null ? envVal : dotEnv.get(key);
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
