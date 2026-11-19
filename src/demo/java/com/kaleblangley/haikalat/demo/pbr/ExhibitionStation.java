package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.subsystems.render3d.RenderFeature;

import java.util.List;
import java.util.Objects;

/**
 * Pure station metadata for the exhibition catalogue.  It owns no GL object,
 * mesh, material or pass: the visible demo and the deterministic integration
 * both read the same records, and the scene wiring lives in the station
 * builders.
 *
 * @param id          stable roadmap station number, 1-46
 * @param wing        themed wing used for navigation
 * @param nameZh      short Chinese name shown on the card
 * @param nameEn      production English name
 * @param feature     production feature-control entry, or {@code null} when the
 *                    effect is configured through material/consumer settings
 * @param subject     the observation subject and A/B contrast
 * @param limitations current known limits copied from the delivery matrix
 * @param evidence    documents and test classes that back the station
 * @param gate        device or quality gating that the card must display
 * @param batch       delivery batch that wires the real scene
 */
public record ExhibitionStation(
        int id,
        ExhibitionWing wing,
        String nameZh,
        String nameEn,
        RenderFeature feature,
        String subject,
        List<String> limitations,
        List<String> evidence,
        String gate,
        ExhibitionBatch batch) {

    public ExhibitionStation {
        if (id < 1 || id > 46) {
            throw new IllegalArgumentException("station id out of range: " + id);
        }
        Objects.requireNonNull(wing, "wing");
        nameZh = requireText(nameZh, "nameZh");
        nameEn = requireText(nameEn, "nameEn");
        subject = requireText(subject, "subject");
        limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        gate = gate == null ? "无额外设备门控" : gate;
        Objects.requireNonNull(batch, "batch");
    }

    /** @return {@code true} when the production feature-control snapshot carries this capability. */
    public boolean featureControlled() {
        return feature != null;
    }

    public String displayName() {
        return "#" + (id < 10 ? "0" + id : Integer.toString(id)) + " " + nameZh;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
