plugins {
    id("dev.kikugie.stonecutter")
}

// Keep 26.2 active: src/ holds 26.2 code and older versions are generated from it. The replacements
// below are written for that direction, so switching the active version would not round-trip.
stonecutter active "26.2"

stonecutter parameters {
    // Version differences that are plain renames go here; everything else uses //? if blocks in the code.
    // Each replace(a, b) lists the 1.21.x text first and the 26.x text second.
    replacements {
        string(current.parsed >= "26.1") {
            // Whole calls first: the general render/extract renames further down must not split them.
            replace("PlayerFaceRenderer.draw(", "PlayerFaceExtractor.extractRenderState(")
            replace("import net.minecraft.client.gui.components.PlayerFaceRenderer;", "import net.minecraft.client.gui.components.PlayerFaceExtractor;")
            replace("renderPanorama(", "extractPanorama(")
            replace("renderBlurredBackground(", "extractBlurredBackground(")
            replace("renderMenuBackground(", "extractMenuBackground(")
            // 26.1 added a flag for whether the options screen was opened from a world.
            replace("new OptionsScreen(parent, mc().options)", "new OptionsScreen(parent, mc().options, false)")

            // Classes that moved to another package in 26.1.
            replace("net.minecraft.client.GuiMessage", "net.minecraft.client.multiplayer.chat.GuiMessage")
            replace("net.minecraft.client.gui.render.state.GuiTextRenderState", "net.minecraft.client.renderer.state.gui.GuiTextRenderState")
            replace("net.minecraft.client.renderer.state.CameraRenderState", "net.minecraft.client.renderer.state.level.CameraRenderState")
            replace("net.minecraft.client.renderer.state.WeatherRenderState", "net.minecraft.client.renderer.state.level.WeatherRenderState")

            // The GUI drawing class and the HUD were renamed; rendering methods went from render* to extract*.
            replace("GuiGraphics", "GuiGraphicsExtractor")
            replace("import net.minecraft.client.gui.Gui;", "import net.minecraft.client.gui.Hud;")
            replace("Gui.getMobEffectSprite(", "Hud.getMobEffectSprite(")
            replace("renderBackground(", "extractBackground(")
            replace("renderImage(", "extractImage(")
            replace(".renderItemDecorations(", ".itemDecorations(")
            replace("render(", "extractRenderState(")

            // Minecraft keeps the open screen and the HUD behind Minecraft.gui since 26.1.
            replace(".screen", ".gui.screen()")
            replace(".setScreen(", ".gui.setScreen(")
            replace(".gui.", ".gui.hud.")
            replace(".gameRenderer.getMainCamera()", ".gameRenderer.mainCamera()")
            replace(".nonEmptyStream().map(ItemStack::copy)", ".nonEmptyItemCopyStream()")
            replace(".addMessage(", ".addClientSystemMessage(")

            // Fabric API renames.
            replace("keybinding.v1.KeyBindingHelper", "keymapping.v1.KeyMappingHelper")
            replace("KeyBindingHelper.registerKeyBinding(", "KeyMappingHelper.registerKeyMapping(")
            replace("TooltipComponentCallback", "ClientTooltipComponentCallback")
            replace("Screens.getButtons(", "Screens.getWidgets(")
            replace("ScreenEvents.afterRender(", "ScreenEvents.afterExtract(")
        }
        regex(current.parsed >= "26.1") {
            // GuiGraphicsExtractor.text(...) is drawString(...) in 1.21.x; Theme.text() takes no arguments and stays.
            replace("\\.drawString\\(", ".text(", "\\.text\\((?!\\))", ".drawString(")
            replace("\\.renderItem\\(", ".item(", "\\.item\\((?!\\))", ".renderItem(")
        }
    }
}
