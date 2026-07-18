package com.kaleblangley.haikalat.demo.gltf;

import com.kaleblangley.haikalat.subsystems.ui.UiNode;
import com.kaleblangley.haikalat.subsystems.ui.layout.YogaLayoutEngine;
import com.kaleblangley.haikalat.subsystems.ui.style.UiLength;
import com.kaleblangley.haikalat.subsystems.ui.style.UiStyle;
import com.kaleblangley.haikalat.subsystems.ui.widget.Panel;
import com.kaleblangley.haikalat.subsystems.ui.widget.ScrollView;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GltfDemoOverlayLayoutTest {
    @Test
    void longInspectionTreeKeepsLineHeightAndProducesScrollableExtent() {
        var lines = IntStream.range(0, 48).mapToObj(index -> "node[" + index + "]").toList();
        Panel content = GltfDemoOverlay.createInspectionContent(lines);
        ScrollView scroll = new ScrollView();
        scroll.style(UiStyle.builder()
                .width(UiLength.points(560))
                .height(UiLength.points(300))
                .flexShrink(0)
                .build());
        scroll.content(content);

        try (YogaLayoutEngine layout = new YogaLayoutEngine()) {
            layout.layout(scroll, 560, 300);
        }

        assertTrue(content.layoutBox().height() > scroll.layoutBox().height());
        assertTrue(scroll.maxScrollY() > 600.0);
        assertEquals(48, content.children().size());
        for (UiNode row : content.children()) {
            assertEquals(20.0, row.layoutBox().height(), 0.01);
        }
    }
}
