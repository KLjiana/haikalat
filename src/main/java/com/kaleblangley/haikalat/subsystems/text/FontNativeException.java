package com.kaleblangley.haikalat.subsystems.text;

/** FreeType 或 HarfBuzz 操作失败时携带操作名和原始错误码的异常。 */
public final class FontNativeException extends IllegalStateException {
    private final String operation;
    private final int errorCode;

    public FontNativeException(String operation, int errorCode) {
        super(operation + " failed with native error " + errorCode);
        this.operation = operation;
        this.errorCode = errorCode;
    }

    public FontNativeException(String operation, String message) {
        super(operation + " failed: " + message);
        this.operation = operation;
        this.errorCode = -1;
    }

    public String operation() {
        return operation;
    }

    public int errorCode() {
        return errorCode;
    }
}
