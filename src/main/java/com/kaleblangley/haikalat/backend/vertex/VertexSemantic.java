package com.kaleblangley.haikalat.backend.vertex;

/** 顶点属性的引擎侧语义；backend 绑定仍只使用显式 attribute location。 */
public enum VertexSemantic {
    POSITION,
    TEXCOORD_0,
    NORMAL,
    TANGENT,
    COLOR_0,
    CUSTOM
}
