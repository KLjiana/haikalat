package com.kaleblangley.haikalat.demo.ui;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Properties;

/** 从 UTF-8 Demo 资源加载用户可见字符串。 */
record UiDemoStrings(String title, String subtitle, String controls,
                     String actionButton, String menuButton, String toggle,
                     String slider, String textPlaceholder, String scrollTitle,
                     String nestedTitle, String listTitle, String menuFirst,
                     String menuSecond, String footerReady) {
    private static final String RESOURCE = "/ui/ui-demo.properties";

    static UiDemoStrings load() {
        Properties properties = new Properties();
        try (var input = UiDemoStrings.class.getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing UI Demo resource: " + RESOURCE);
            try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read UI Demo resource: " + RESOURCE, failure);
        }
        return new UiDemoStrings(required(properties, "title"),
                required(properties, "subtitle"), required(properties, "controls"),
                required(properties, "actionButton"), required(properties, "menuButton"),
                required(properties, "toggle"), required(properties, "slider"),
                required(properties, "textPlaceholder"), required(properties, "scrollTitle"),
                required(properties, "nestedTitle"), required(properties, "listTitle"),
                required(properties, "menuFirst"), required(properties, "menuSecond"),
                required(properties, "footerReady"));
    }

    private static String required(Properties properties, String key) {
        String value = Objects.requireNonNull(properties.getProperty(key),
                () -> "Missing UI Demo text property: " + key).trim();
        if (value.isEmpty()) throw new IllegalStateException("Blank UI Demo text property: " + key);
        return value;
    }
}
