package com.kaleblangley.haikalat.subsystems.ui.text;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FontNativeLifecycleTest {
    @Test
    void publicApiDoesNotExposeNativePointers() {
        for (Class<?> type : List.of(FontManager.class, FontFace.class, TextShaper.class)) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    assertFalse(method.getName().toLowerCase().contains("handle"), method::toString);
                    assertFalse(method.getName().toLowerCase().contains("address"), method::toString);
                }
            }
        }
    }

    @Test
    void invalidFontDoesNotPoisonManagerAndCloseIsIdempotent() {
        FontManager manager = new FontManager();
        FontFamily family = manager.registerFamily("invalid-test");

        assertThrows(FontNativeException.class,
                () -> manager.registerFace(family, ByteBuffer.wrap(new byte[]{1, 2, 3, 4}), 0));
        assertFalse(manager.isClosed());
        manager.close();
        assertTrue(manager.isClosed());
        assertDoesNotThrow(manager::close);
    }

    @Test
    void faceAndShaperAreThreadConfined() throws Exception {
        Path font = requireConditionalFont();
        try (FontManager manager = new FontManager()) {
            FontFace face = manager.registerFace(manager.registerFamily("thread-test"), font, 0);
            TextShaper shaper = new TextShaper(manager, 8);
            AtomicReference<Throwable> faceFailure = new AtomicReference<>();
            AtomicReference<Throwable> shaperFailure = new AtomicReference<>();
            Thread foreign = new Thread(() -> {
                try {
                    face.supportsCodePoint('A');
                } catch (Throwable failure) {
                    faceFailure.set(failure);
                }
                try {
                    shaper.shape(face, 16, "A");
                } catch (Throwable failure) {
                    shaperFailure.set(failure);
                }
            }, "foreign-font-thread");
            foreign.start();
            foreign.join();

            assertTrue(faceFailure.get() instanceof IllegalStateException, () -> String.valueOf(faceFailure.get()));
            assertTrue(shaperFailure.get() instanceof IllegalStateException, () -> String.valueOf(shaperFailure.get()));
            shaper.close();
        }
    }

    @Test
    void registrationFallbackGenerationAndReverseCloseRemainStable() throws Exception {
        Path font = requireConditionalFont();
        FontManager manager = new FontManager();
        FontGeneration initial = manager.generation();
        FontFamily firstFamily = manager.registerFamily("primary");
        FontFace firstFace = manager.registerFace(firstFamily, font, 0);
        FontFallbackChain chain = manager.fallbackChain(firstFace);

        assertTrue(manager.generation().value() > initial.value());
        assertEquals(firstFace, manager.resolveFaceOrTofu(chain, "A", 0, 1));
        assertTrue(firstFace.supportsCodePoint('A'));
        assertTrue(firstFace.covers("A", 0, 1));

        FontFamily secondFamily = manager.registerFamily("secondary");
        FontFace secondFace = manager.registerFace(secondFamily, font, 0);
        assertNotEquals(firstFace.id(), secondFace.id());
        assertThrows(IllegalStateException.class,
                () -> manager.resolveFaceOrTofu(chain, "A", 0, 1));

        FontGeneration beforeClose = manager.generation();
        secondFace.close();
        assertTrue(secondFace.isClosed());
        assertTrue(manager.generation().value() > beforeClose.value());
        assertDoesNotThrow(secondFace::close);
        manager.close();
        assertTrue(firstFace.isClosed());
        assertDoesNotThrow(manager::close);
    }

    private static Path requireConditionalFont() {
        Optional<Path> font = conditionalFont();
        Assumptions.assumeTrue(font.isPresent(),
                "No system/JDK font is available; deterministic project font asset is not installed yet");
        return font.orElseThrow();
    }

    static Optional<Path> conditionalFont() {
        String javaHome = System.getProperty("java.home", "");
        List<Path> candidates = List.of(
                Path.of("C:/Windows/Fonts/arial.ttf"),
                Path.of("C:/Windows/Fonts/segoeui.ttf"),
                Path.of("C:/Windows/Fonts/consola.ttf"),
                Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
                Path.of("/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf"),
                Path.of("/System/Library/Fonts/Supplemental/Arial.ttf"),
                Path.of(javaHome, "lib", "fonts", "LucidaSansRegular.ttf"));
        return candidates.stream().filter(Files::isRegularFile).findFirst();
    }
}
