package com.kaleblangley.haikalat.demo.pbr;

import java.util.List;

/**
 * One themed exhibition wing.  Wings are the navigation grouping from the
 * realtime-rendering roadmap; they own no renderer and only order stations.
 */
public enum ExhibitionWing {
    A("A", "战斗特效表现", 1, 2, 3, 5, 7),
    B("B", "特效与场景交互", 4, 6, 9, 10),
    C("C", "材质与局部光照", 8, 16, 17, 18, 19, 31),
    D("D", "水体、折射与反射", 11, 13, 14, 20, 30, 36),
    E("E", "镜头与最终画面", 12, 21, 22, 23, 24, 25),
    F("F", "粒子规模与重建", 15, 26, 27, 28, 34, 35, 41, 42),
    G("G", "专项能力", 29, 32, 33, 38, 40, 43, 46),
    H("H", "高成本研究能力", 37, 39, 44, 45);

    private final String label;
    private final String title;
    private final int[] stationIds;

    ExhibitionWing(String label, String title, int... stationIds) {
        this.label = label;
        this.title = title;
        this.stationIds = stationIds;
    }

    public String label() {
        return label;
    }

    public String title() {
        return title;
    }

    /** @return station IDs in roadmap order; the returned array is a defensive copy. */
    public int[] stationIds() {
        return stationIds.clone();
    }

    public List<Integer> stationIdList() {
        return java.util.Arrays.stream(stationIds).boxed().toList();
    }

    public String displayName() {
        return label + " · " + title;
    }
}
