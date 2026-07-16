package com.kaleblangley.haikalat.subsystems.windowing.input;

import java.util.Optional;

/** 与 GLFW/操作系统实现解耦的 Unicode plain-text 剪贴板。 */
public interface ClipboardService {
    Optional<String> readText();

    void writeText(String text);
}
