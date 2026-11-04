package com.kaleblangley.haikalat.demo.ui;

import com.kaleblangley.haikalat.subsystems.ui.*;
import com.kaleblangley.haikalat.subsystems.ui.animation.UiVisualTransform;
import com.kaleblangley.haikalat.subsystems.ui.style.*;
import com.kaleblangley.haikalat.subsystems.ui.text.UiTextEngine;
import com.kaleblangley.haikalat.subsystems.ui.widget.*;
import com.kaleblangley.haikalat.subsystems.ui.render.UiLayerDescription;
import com.kaleblangley.haikalat.subsystems.ui.render.UiScreenRect;

import java.util.LinkedHashMap;
import java.util.Map;

/** VLS business content over an explicitly sourced public compositor backdrop. */
final class VlsDemoScene {
    private static final String[] TITLES = {"建立营地", "穿过云杉林", "山脊上的信号", "林间栖居", "更远的地方"};
    private static final String[] SHORT = {"扎根", "寻路", "信号", "栖居", "远行"};
    private static final String[] DESCRIPTIONS = {
            "篝火已经点燃。补给与休息点已标记，\n这里是下一段旅途的起点。",
            "沿溪流向北，寻找林间的旧石桥。\n树冠会遮住山脊，水声会为你引路。",
            "越过石桥，登上东侧山脊。\n修复信号台，让营地看见你的方向。",
            "在林间找到一处可以停留的地方。\n为漫长的旅程准备一个温暖的家。",
            "当你准备好，再向未知的世界出发。\n每一步，都将成为新的故事。"};
    private static final String[] ENVIRONMENTS = {"河畔林地", "明亮雪原", "幽暗洞穴", "熔岩山谷"};
    private static final String[] ITEMS = {"橡木", "石剑", "面包", "火把", "圆石"};
    private static final String[] ITEM_INFO = {"搭建通路与临时庇护所。\n数量：32  /  来源：林地采集",
            "守护旅途的简单工具。\n数量：1  /  来源：营地制作", "出发前补充体力。\n数量：6  /  来源：营地补给",
            "为黑暗中的道路留下光。\n数量：16  /  来源：营地制作", "稳固的建筑材料。\n数量：64  /  来源：山麓采集"};
    final Map<String, Button> controls = new LinkedHashMap<>();
    private final Panel backdrop=new Panel();
    private final UiScreenRect backdropBounds;
    private final Panel detail, detailRail, taskList, inventory, footer;
    private final Label detailTitle, description, state, objective, progress, status, location, pageTitle;
    private final Button action, motion, environment, page, blur;
    private final Button[] tasks = new Button[5];
    private final Label[] taskStates = new Label[5];
    private final Panel[] selectionMarks = new Panel[5], lockIcons = new Panel[5];
    private boolean tracked, completed, backpack, reducedMotion, blurEnabled = true;
    private int selected = 1, environmentIndex;
    private float entrance, time;
    private double entranceOffset = 12;

    static UiConfig config() {
        return UiConfig.builder().primitiveCapacity(2048, 16384).glyphAtlas(1024, 1024, 8)
                .styleResolver((widget, classes, states, inherited) -> {
                    UiColor ink = inherited != null && widget.equals("Label") ? inherited.foreground() : color(0xF1F4F4ff);
                    UiColor bg = UiColor.TRANSPARENT, edge = UiColor.TRANSPARENT;
                    float size = inherited == null ? 14 : inherited.fontSize(), border = 0;
                    if (classes.contains("muted")) ink = color(0xC3CCCEff);
                    if (classes.contains("small")) { size = 12; ink = color(0xC3CCCEff); }
                    if (classes.contains("title")) size = 32;
                    if (classes.contains("heading")) size = 24;
                    if (classes.contains("brand")) size = 26;
                    if (classes.contains("icon")) size = 26;
                    if (classes.contains("accent")) ink = color(0xD6AE6Bff);
                    if (classes.contains("success")) ink = color(0x79B78Fff);
                    if (classes.contains("warning")) ink = color(0xE59A65ff);
                    // Deliberately low fills: structure stays in the edges, world shows through.
                    if (classes.contains("glass")) bg = color(0x0C121514);
                    if (classes.contains("detail-glass")) bg = color(0x0C12151c);
                    if (classes.contains("reading")) bg = color(0x0C12154d);
                    if (classes.contains("rail")) bg = color(0xCAD4D64d);
                    if (classes.contains("gold-rail")) bg = color(0xD6AE6Bff);
                    if (classes.contains("icon-stroke")) { edge = color(0xCAD4D6dd); border = 1; }
                    for (String role : classes) if (role.startsWith("pixel:")) bg = color((int) Long.parseLong(role.substring(6), 16));
                    if (classes.contains("button")) {
                        bg = color(0x0C12150d);
                        if (classes.contains("node") || classes.contains("slot") || classes.contains("primary")) {
                            edge = color(0xCAD4D680); border = 1;
                        }
                        if (classes.contains("node")) size = 25;
                        if (classes.contains("selected")) { edge = color(0xF1F4F4cc); border = 1; bg = color(0xCAD4D618); }
                        if (states.contains(StyleResolver.PseudoState.HOVER)) { bg = color(0xCAD4D624); edge = color(0xCAD4D6cc); border = 1; }
                        if (states.contains(StyleResolver.PseudoState.FOCUSED)) { edge = color(0xD6AE6Bff); border = 2; }
                        if (states.contains(StyleResolver.PseudoState.PRESSED)) bg = color(0xCAD4D642);
                        if (states.contains(StyleResolver.PseudoState.DISABLED)) { ink = color(0x94A0A3ff); edge = color(0xCAD4D633); border = 1; bg = UiColor.TRANSPARENT; }
                    }
                    return new ComputedStyle(bg, ink, edge, border, 0, 1, size, UiTextEngine.DEFAULT_FONT_FAMILY);
                }).build();
    }

    VlsDemoScene(UiSystem ui, int initialEnvironment, boolean reduced, int width, int height) {
        reducedMotion = reduced;
        backdropBounds=new UiScreenRect(0,0,width,height);
        backdrop.style(UiStyle.builder().positionType(UiStyle.PositionType.ABSOLUTE).width(pct(100)).height(pct(100)).build());
        backdrop.hitTestVisible(false);
        ui.document().root().style(UiStyle.builder().width(pct(100)).height(pct(100)).build());
        Panel root = new Panel();
        ui.document().root().add(backdrop).add(root);
        root.style(UiStyle.builder().width(pct(100)).height(pct(100))
                .padding(new UiInsets(pct(7), pt(24), pct(7), pt(20))).gap(18).build());

        Panel header = row(24);
        Label brand = text("◇  原野", "brand"); header.add(brand);
        header.add(text("W I L D F I E L D", "small"));
        header.add(spacer());
        page = button("page", "行囊", () -> setBackpack(!backpack));
        Button journey = button("journey", "旅程", () -> setBackpack(false));
        journey.addStyleClass("selected");
        header.add(page).add(journey).add(text("VLS / 交互原型", "small"));
        root.add(header).add(rail(false));

        Panel hero = row(20);
        hero.style(UiStyle.builder().width(pct(100)).padding(UiInsets.points(0, 18))
                .flexDirection(UiStyle.FlexDirection.ROW).alignItems(UiStyle.AlignItems.CENTER).gap(20).build());
        Panel introduction = column(10);
        introduction.add(text("循迹而行 · JOURNEY", "small"));
        pageTitle = text("从这里，开始你的世界。", "title");
        introduction.add(pageTitle).add(text("小小的目标，连成一段值得记住的旅程。", "muted"));
        location = text("", "small"); location.wrap(Label.Wrap.WORD);
        location.style(UiStyle.builder().width(pt(160)).build());
        hero.add(introduction).add(spacer()).add(location); root.add(hero);

        Panel body = row(28);
        body.style(UiStyle.builder().width(pct(100)).flexGrow(1).flexDirection(UiStyle.FlexDirection.ROW)
                .alignItems(UiStyle.AlignItems.CENTER).gap(28).build());
        Panel offset = column(0); offset.style(UiStyle.builder().width(pct(8)).flexShrink(1).build()); body.add(offset);

        taskList = column(0); taskList.addStyleClass("glass");
        taskList.style(UiStyle.builder().flexGrow(1).minWidth(pt(340)).flexShrink(1).build());
        taskList.add(corners(false));
        Panel journeyContent = column(22);
        journeyContent.style(UiStyle.builder().padding(UiInsets.points(22, 16)).gap(22).build());
        Panel chapter = row(8); chapter.add(text("第一章 · 在此扎根", "muted")).add(spacer());
        progress = text("", "small"); chapter.add(progress); journeyContent.add(chapter);
        Panel nodeRow = row(0);
        nodeRow.style(UiStyle.builder().width(pct(100)).padding(UiInsets.points(6, 28))
                .flexDirection(UiStyle.FlexDirection.ROW).alignItems(UiStyle.AlignItems.FLEX_START).build());
        for (int i = 0; i < 5; i++) {
            final int n = i;
            Panel node = column(12);
            node.style(UiStyle.builder().width(pt(58)).flexShrink(0).alignItems(UiStyle.AlignItems.CENTER).gap(12).build());
            tasks[i] = button("task" + i, "", () -> select(n));
            tasks[i].addStyleClass("node");
            tasks[i].style(UiStyle.builder().width(pt(56)).height(pt(56)).flexShrink(0)
                    .alignItems(UiStyle.AlignItems.FLEX_START).justifyContent(UiStyle.JustifyContent.FLEX_START).build());
            Label glyph = (Label) tasks[i].children().getFirst(); glyph.alignment(Label.Alignment.CENTER);
            glyph.style(UiStyle.builder().width(pct(100)).height(pt(34))
                    .margin(new UiInsets(pt(0), pt(10), pt(0), pt(0))).build());
            selectionMarks[i] = absolute(0, 0, 20, 2, "gold-rail");
            lockIcons[i] = lockIcon(); tasks[i].add(lockIcons[i]).add(selectionMarks[i]);
            taskStates[i] = text("", "small");
            node.add(tasks[i]).add(text(SHORT[i], "muted")).add(taskStates[i]); nodeRow.add(node);
            if (i < 4) {
                Panel link = rail(false);
                link.style(UiStyle.builder().height(pt(1)).flexGrow(1).minWidth(pt(4))
                        .margin(new UiInsets(pt(0), pt(28), pt(0), pt(0))).build());
                nodeRow.add(link);
            }
        }
        journeyContent.add(nodeRow).add(rail(false)).add(text("从一块原木开始，慢慢建立你的世界。", "small"));
        taskList.add(journeyContent).add(corners(true)); body.add(taskList);

        inventory = column(18); inventory.style(taskList.style()); inventory.addStyleClass("glass");
        inventory.add(corners(false));
        Panel inventoryContent = column(20); inventoryContent.style(UiStyle.builder().padding(UiInsets.points(24)).gap(20).build());
        inventoryContent.add(text("随身行囊", "heading")).add(text("每一件物品，都有它的来处。", "muted"));
        Panel itemRow = row(14);
        for (int i = 0; i < ITEMS.length; i++) {
            final int n = i;
            Panel item = column(10); item.style(UiStyle.builder().alignItems(UiStyle.AlignItems.CENTER).gap(10).build());
            Button itemButton = itemButton("item" + i, i, () -> showItem(n));
            item.add(itemButton).add(text(ITEMS[i], "small")); itemRow.add(item);
        }
        inventoryContent.add(itemRow).add(rail(false)).add(text("选择物品，查看来源与用途。", "small"));
        inventory.add(inventoryContent).add(corners(true)); inventory.visibility(UiVisibility.COLLAPSED); body.add(inventory);

        detail = column(16); detail.addStyleClass("detail-glass");
        detail.style(UiStyle.builder().width(pt(300)).flexShrink(0).padding(UiInsets.points(22, 14)).gap(16).build());
        detailRail = rail(true); detail.add(detailRail);
        state = text("", "small"); detailTitle = text("", "heading");
        description = text("", "muted"); description.wrap(Label.Wrap.WORD);
        Panel reading = column(0); reading.addStyleClass("reading");
        reading.style(UiStyle.builder().padding(UiInsets.points(12, 10)).build()); reading.add(description);
        objective = text("", "muted"); objective.wrap(Label.Wrap.WORD);
        action = button("action", "", this::act); action.addStyleClass("primary");
        Panel actionGroup = column(0); actionGroup.add(action).add(rail(true));
        detail.add(state).add(detailTitle).add(reading).add(text("旅程目标", "small")).add(objective)
                .add(rail(false)).add(text("完成后，下一段道路会向你展开。", "small")).add(actionGroup);
        body.add(detail); root.add(body);

        Panel hotbar = row(6);
        hotbar.style(UiStyle.builder().alignSelf(UiStyle.AlignItems.CENTER).flexDirection(UiStyle.FlexDirection.ROW).gap(6).build());
        for (int i = 0; i < 5; i++) {
            final int n = i; hotbar.add(itemButton("slot" + i, i, () -> { setBackpack(true); showItem(n); }));
        }
        root.add(hotbar);

        footer = column(10);
        status = text("世界是实体，UI 是标记。选择节点，展开你的旅程。", "small");
        Panel footerRow = row(10);
        environment = button("environment", "", () -> setEnvironment((environmentIndex + 1) % 4));
        motion = button("motion", "", this::toggleMotion);
        blur = button("blur", "", () -> setBlur(!blurEnabled));
        footerRow.add(environment).add(blur).add(motion).add(spacer()).add(text("Tab 切换焦点 · Enter 确认 · Esc 退出", "small"));
        footer.add(rail(false)).add(footerRow).add(status); root.add(footer);
        setEnvironment(initialEnvironment); setBlur(true);
        motion.text(reduced ? "动效：减少" : "动效：完整"); refresh();
    }

    private void select(int index) {
        selected = index; refresh(); beginTransition();
        status.text("已展开：" + TITLES[index] + " / 详情来自旅程中的同名节点。");
    }
    private void toggleMotion() {
        reducedMotion = !reducedMotion; motion.text(reducedMotion ? "动效：减少" : "动效：完整");
        if (reducedMotion) { entrance = 1; detail.visualTransform(UiVisualTransform.IDENTITY); }
    }
    private void beginTransition() {
        entranceOffset = entrance < 1 ? detail.visualTransform().translateX() : 12;
        entrance = reducedMotion ? 1 : 0;
    }
    private void refresh() {
        for (int i = 0; i < tasks.length; i++) {
            boolean done = i == 0 || i == 1 && completed;
            boolean locked = i >= 3 || i == 2 && !completed;
            tasks[i].removeStyleClass("selected"); tasks[i].removeStyleClass("success");
            tasks[i].text(done ? "✓" : locked ? "" : "◇");
            tasks[i].semantics(UiSemanticRole.BUTTON, TITLES[i], locked ? "锁定" : done ? "已完成" : "可追踪");
            lockIcons[i].visibility(locked ? UiVisibility.VISIBLE : UiVisibility.HIDDEN);
            selectionMarks[i].visibility(i == selected ? UiVisibility.VISIBLE : UiVisibility.HIDDEN);
            if (i == selected) tasks[i].addStyleClass("selected");
            if (done) tasks[i].addStyleClass("success");
            taskStates[i].text(done ? "已完成" : locked ? "待解锁" : "进行中");
        }
        boolean done = selected == 0 || selected == 1 && completed;
        boolean locked = selected >= 3 || selected == 2 && !completed;
        state.removeStyleClass("success"); state.removeStyleClass("warning");
        state.text(done ? "✓ 已完成 · 旅途记录" : locked ? "锁定 · 需要前置任务" : "第一章 · 当前旅程");
        if (done) state.addStyleClass("success");
        if (locked) state.addStyleClass("warning");
        detailTitle.text(TITLES[selected]); description.text(DESCRIPTIONS[selected]);
        objective.text(done ? "这段旅途已经记入手记。" : locked ? "先完成「" + TITLES[selected - 1] + "」。" :
                selected == 2 ? "沿东侧山路，寻找信号台。" : tracked ? "正在追踪林间旧石桥。\n可模拟抵达，解锁下一站。" : "找到林间旧石桥。\n沿溪流向北行进。");
        action.enabled(!done && !locked);
        action.text(done ? "✓ 已记录" : locked ? "前置任务尚未完成" : selected == 2 ? "追踪信号台" : tracked ? "模拟抵达石桥" : "追踪这段旅途");
        progress.text((completed ? 2 : 1) + " / 5 已完成");
    }
    private void act() {
        if (selected == 1 && !completed) {
            if (tracked) { completed = true; status.text("✓ 已抵达石桥。「山脊上的信号」已解锁。"); }
            else { tracked = true; status.text("正在追踪「穿过云杉林」：沿溪流向北寻找旧石桥。"); }
        } else if (selected == 2 && completed) status.text("正在追踪「山脊上的信号」：沿东侧山路登顶。");
        refresh();
    }
    private void setBackpack(boolean value) {
        backpack = value;
        taskList.visibility(value ? UiVisibility.COLLAPSED : UiVisibility.VISIBLE);
        inventory.visibility(value ? UiVisibility.VISIBLE : UiVisibility.COLLAPSED);
        page.text(value ? "返回旅程" : "行囊");
        pageTitle.text(value ? "带上行囊，继续向前。" : "从这里，开始你的世界。");
        controls.get("journey").removeStyleClass("selected"); page.removeStyleClass("selected");
        (value ? page : controls.get("journey")).addStyleClass("selected");
        if (value) showItem(0); else refresh();
        beginTransition();
    }
    private void showItem(int item) {
        detailTitle.text(ITEMS[item]); description.text(ITEM_INFO[item]); state.text("行囊 · 随身携带");
        state.removeStyleClass("success"); state.removeStyleClass("warning");
        objective.text("物品详情来自选中的行囊格槽。"); action.text("携带中"); action.enabled(false);
        for (int i = 0; i < ITEMS.length; i++) {
            controls.get("item" + i).removeStyleClass("selected"); controls.get("slot" + i).removeStyleClass("selected");
        }
        controls.get("item" + item).addStyleClass("selected"); controls.get("slot" + item).addStyleClass("selected");
        status.text("正在查看：" + ITEMS[item]); beginTransition();
    }
    void setEnvironment(int value) {
        environmentIndex = value; environment.text("环境：" + ENVIRONMENTS[value]);
        location.text("◇ " + ENVIRONMENTS[value] + "\n第 24 日 · " + (value == 0 ? "晨光" : "环境预览"));
    }
    void setBlur(boolean value) {
        blurEnabled = value; blur.text(value ? "背景：柔焦" : "背景：清晰");
        backdrop.layerDescription(backdropDescription());
    }
    UiLayerDescription backdropDescription() {
        return UiLayerDescription.builder("vls_backdrop",backdropBounds).backdropBlur(blurEnabled?20:0,2).build();
    }
    boolean blurEnabled() { return blurEnabled; }
    void update(float delta) {
        if (!reducedMotion) time += Math.min(delta, .05f);
        entrance = Math.min(1, entrance + delta / .2f);
        detail.visualTransform(UiVisualTransform.translation(reducedMotion ? 0 : entranceOffset * Math.pow(1 - entrance, 3), 0));
        double stage = reducedMotion ? 1 : entrance < .33f ? .33 : entrance < .66f ? .66 : 1;
        detailRail.visualTransform(new UiVisualTransform(0, 0, stage, 1, 0, 0, 0));
    }
    int environment() { return environmentIndex; }
    float time() { return time; }
    boolean completed() { return completed; }
    boolean reducedMotion() { return reducedMotion; }
    void verifyLayout() {
        if (taskList.layoutBox().bottom() + 12 > footer.layoutBox().y() || detail.layoutBox().bottom() + 12 > footer.layoutBox().y())
            throw new IllegalStateException("Journey/detail panels overlap footer");
        for (Button task : tasks) if (task.layoutBox().right() > taskList.layoutBox().right())
            throw new IllegalStateException("Task node overflows journey panel");
    }
    private Button button(String id, String label, Runnable callback) {
        Button b = new Button(label); b.debugName(id); b.addStyleClass("button");
        b.style(UiStyle.builder().height(pt(44)).padding(UiInsets.points(12, 8)).flexShrink(0)
                .justifyContent(UiStyle.JustifyContent.CENTER).alignItems(UiStyle.AlignItems.CENTER).build());
        b.onClick(callback); controls.put(id, b); return b;
    }
    private Button itemButton(String id, int item, Runnable callback) {
        Button b = button(id, "", callback); b.addStyleClass("slot");
        b.style(UiStyle.builder().width(pt(48)).height(pt(48)).flexShrink(0).build());
        b.semantics(UiSemanticRole.BUTTON, ITEMS[item], "查看物品");
        String[][] shapes = {
                {"  BBBB  "," BBBBBB "," BBCCBB "," BBCCBB "," BBCCBB "," BBCCBB "," BBBBBB ","  BBBB  "},
                {"     SS ","    SSS ","   SSS  ","  SSS   "," BSS    "," BB     ","B B     ","B       "},
                {"        ","  DDDD  "," DDEEDD ","DDEEDDDD","DDDDDDDD"," DDDDDD ","        ","        "},
                {"   EE   ","  EFFE  ","  EFFE  ","   DD   ","   BB   ","   BB   ","   BB   ","   BB   "},
                {"  SSSS  "," SSSSSS ","SSCCSSSS","SSSSSCSS","SCSSSSSS","SSSSCCSS"," SSSSSS ","  SSSS  "}
        };
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) {
            char pixel = shapes[item][y].charAt(x);
            String color = switch (pixel) { case 'B' -> "95704cff"; case 'C' -> "616e68ff"; case 'D' -> "c19b61ff";
                case 'E' -> "e2bb75ff"; case 'F' -> "f8df9aff"; case 'S' -> "b9c9c2ff"; default -> null; };
            if (color != null) b.add(absolute(8 + x * 4, 8 + y * 4, 4, 4, "pixel:" + color));
        }
        return b;
    }
    private static Panel lockIcon() {
        Panel p = absolute(17, 16, 22, 26, "");
        p.add(absolute(5, 0, 10, 10, "icon-stroke"));
        p.add(absolute(1, 8, 18, 15, "icon-stroke"));
        p.add(absolute(9, 13, 2, 5, "rail")); return p;
    }
    private static Panel absolute(float x, float y, float w, float h, String role) {
        Panel p = column(0); if (!role.isEmpty()) p.addStyleClass(role);
        p.style(UiStyle.builder().positionType(UiStyle.PositionType.ABSOLUTE).width(pt(w)).height(pt(h))
                .margin(new UiInsets(pt(x), pt(y), pt(0), pt(0))).build()); return p;
    }
    private static Panel corners(boolean bottom) {
        Panel line = row(0);
        line.style(UiStyle.builder().width(pct(100)).height(pt(12)).flexShrink(0).flexDirection(UiStyle.FlexDirection.ROW).build());
        for (int i = 0; i < 2; i++) {
            Panel c = column(0); c.style(UiStyle.builder().width(pt(18)).height(pt(12)).flexShrink(0).build());
            c.add(absolute(0, bottom ? 11 : 0, 18, 1, "rail")).add(absolute(i == 0 ? 0 : 17, 0, 1, 12, "rail"));
            line.add(c); if (i == 0) line.add(spacer());
        }
        return line;
    }
    private static Label text(String value, String role) { Label l = new Label(value); l.addStyleClass(role); return l; }
    private static Panel column(float gap) { Panel p = new Panel(); p.hitTestVisible(false); p.style(UiStyle.builder().gap(gap).build()); return p; }
    private static Panel row(float gap) { Panel p = column(gap); p.style(UiStyle.builder().flexDirection(UiStyle.FlexDirection.ROW).alignItems(UiStyle.AlignItems.CENTER).gap(gap).build()); return p; }
    private static Panel spacer() { Panel p = column(0); p.style(UiStyle.builder().flexGrow(1).build()); return p; }
    private static Panel rail(boolean gold) { Panel p = column(0); p.style(UiStyle.builder().width(gold ? pt(32) : pct(100)).height(pt(gold ? 2 : 1)).flexShrink(0).build()); p.addStyleClass(gold ? "gold-rail" : "rail"); return p; }
    private static UiLength pt(float n) { return UiLength.points(n); }
    private static UiLength pct(float n) { return UiLength.percent(n); }
    private static UiColor color(int rgba) { return UiColor.fromSrgbHex(rgba); }
}
