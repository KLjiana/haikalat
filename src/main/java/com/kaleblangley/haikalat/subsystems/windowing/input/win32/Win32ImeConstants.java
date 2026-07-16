package com.kaleblangley.haikalat.subsystems.windowing.input.win32;

/** Win32 IME 消息和 ImmGetCompositionStringW 标志。 */
final class Win32ImeConstants {
    static final int WM_IME_STARTCOMPOSITION = 0x010D;
    static final int WM_IME_ENDCOMPOSITION = 0x010E;
    static final int WM_IME_COMPOSITION = 0x010F;

    static final int GCS_COMPSTR = 0x0008;
    static final int GCS_COMPATTR = 0x0010;
    static final int GCS_CURSORPOS = 0x0080;
    static final int GCS_RESULTSTR = 0x0800;

    static final int ATTR_TARGET_CONVERTED = 0x01;
    static final int ATTR_TARGET_NOTCONVERTED = 0x03;

    static final int CFS_POINT = 0x0002;
    static final int CFS_CANDIDATEPOS = 0x0040;

    private Win32ImeConstants() {
    }
}
