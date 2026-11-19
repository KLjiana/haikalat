package com.kaleblangley.haikalat.demo.pbr;

/**
 * Delivery batch from the exhibition plan.  A station that has no scene
 * builder yet reports its batch instead of faking the effect.
 */
public enum ExhibitionBatch {
    ONE("1 地面与巡览骨架"),
    TWO("2 局部表面展翼 A-C"),
    THREE("3 水与反射展翼 D"),
    FOUR("4 全帧与时域展翼 E/F"),
    FIVE("5 体积与研究展翼 G/H"),
    SIX("6 人工视觉验收");

    private final String title;

    ExhibitionBatch(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}
