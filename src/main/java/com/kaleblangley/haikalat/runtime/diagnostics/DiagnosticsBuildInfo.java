package com.kaleblangley.haikalat.runtime.diagnostics;

import com.kaleblangley.haikalat.backend.GlDebug;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** 从构建生成资源读取版本身份，不把 Gradle 或环境对象泄漏到公共 API。 */
final class DiagnosticsBuildInfo {
    private static final String RESOURCE = "/haikalat-build.properties";
    private static final Properties VALUES = load();

    private DiagnosticsBuildInfo() {
    }

    static FrozenDiagnostics.Metadata capture(GlDebug.ContextInfo context) {
        return new FrozenDiagnostics.Metadata(
                VALUES.getProperty("engineVersion", "development"),
                VALUES.getProperty("buildRevision", "local"),
                context.vendor(), context.renderer(), context.version());
    }

    private static Properties load() {
        Properties result = new Properties();
        try (InputStream input = DiagnosticsBuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (input != null) result.load(input);
        } catch (IOException ignored) {
            // 缺少生成资源时仍导出明确的 development/local 身份。
        }
        return result;
    }
}
