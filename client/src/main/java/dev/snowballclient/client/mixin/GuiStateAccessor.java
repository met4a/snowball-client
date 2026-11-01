package dev.snowballclient.client.mixin;

import net.minecraft.client.gui.GuiGraphicsExtractor;
//? if >=26.1 {
import net.minecraft.client.renderer.state.gui.GuiRenderState;
//?} else {
/*import net.minecraft.client.gui.render.state.GuiRenderState;
*///?}
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Snowglass adds its own element type (a glass panel) to the interface's render state, which the drawing class keeps private. */
@Mixin(GuiGraphicsExtractor.class)
public interface GuiStateAccessor {
	@Accessor("guiRenderState")
	GuiRenderState snowball$guiRenderState();
}
