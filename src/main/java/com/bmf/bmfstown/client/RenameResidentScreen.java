package com.bmf.bmfstown.client;

import com.bmf.bmfstown.network.ModNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 镇民重命名输入屏（资料页"重命名"按钮打开）。
 *
 * <p>预填当前名字，回车提交（服务端复核长度后 setCustomName），ESC 取消。
 * 打开前必须先关掉镇民面板的容器菜单（见
 * {@code ResidentScreen#closeContainerBeforeSwitch} 的交互锁教训）。
 */
public class RenameResidentScreen extends Screen {

    private static final int W = 180;
    private static final int H = 62;

    private final int entityId;
    private final String currentName;
    private EditBox nameBox;

    private RenameResidentScreen(int entityId, String currentName) {
        super(Component.translatable("gui.bmfstown.resident.rename_title"));
        this.entityId = entityId;
        this.currentName = currentName;
    }

    /** 打开输入屏（先关容器再切屏，调用方负责）。 */
    public static void open(int entityId, String currentName) {
        Minecraft.getInstance().setScreen(new RenameResidentScreen(entityId, currentName));
    }

    @Override
    protected void init() {
        int x0 = (this.width - W) / 2;
        int y0 = (this.height - H) / 2;
        this.nameBox = new EditBox(this.font, x0 + 10, y0 + 24, W - 20, 16, Component.empty());
        this.nameBox.setMaxLength(32); // 与服务端口径一致（原版命名牌同款）
        this.nameBox.setValue(this.currentName);
        this.nameBox.setFocused(true);
        this.addRenderableWidget(this.nameBox);
        this.setInitialFocus(this.nameBox);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gfx, mouseX, mouseY, partialTick);
        int x0 = (this.width - W) / 2;
        int y0 = (this.height - H) / 2;
        // 面板：深底 + 金顶线（与簿记搜索屏同一设计语言）
        gfx.fill(x0, y0, x0 + W, y0 + H, 0xF0202028);
        gfx.fill(x0, y0, x0 + W, y0 + 1, 0xFF8A7A55);
        gfx.drawCenteredString(this.font, this.title, this.width / 2, y0 + 9, 0xFFE0C878);
        gfx.drawCenteredString(this.font, I18n.get("gui.bmfstown.resident.rename_hint"),
                this.width / 2, y0 + H - 12, 0xFF8A8A8A);
        this.nameBox.render(gfx, mouseX, mouseY, partialTick);
    }

    /**
     * 键盘事件<b>显式手动分发</b>给 EditBox（照抄 BookkeeperSearchScreen 的已验证写法）：
     * 依赖 Screen 默认的 focused 分发实测收不到字符输入（v0.8.1 "输不进去"根因）。
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
            return true;
        }
        // ESC 取消必须在 EditBox 分发之前：canConsumeInput() 有焦点时吞一切按键，
        // 否则 ESC 到不了 Screen 的默认关闭逻辑
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (this.nameBox.keyPressed(keyCode, scanCode, modifiers)
                || this.nameBox.canConsumeInput()) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.nameBox.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    /** 回车提交：空名视为取消（不发不提示），有效名发包后关屏。 */
    private void submit() {
        String name = this.nameBox.getValue().trim();
        if (!name.isEmpty()) {
            ModNetworking.sendToServer(new ModNetworking.ResidentRenamePayload(
                    this.entityId, name));
        }
        onClose();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
