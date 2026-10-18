package dev.snowballclient.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Nick Hider on the scoreboard sidebar. Each line is the score holder's name inside its team's
 * prefix and suffix, which is where servers often write a player's name, so the whole line is
 * checked. The sidebar builds each line twice, once to measure it and once to draw it; both are
 * swapped, so it is sized for the name it shows.
 */
@Mixin(InGameHud.class)
public class ScoreboardNickMixin {
	@ModifyExpressionValue(method = "renderScoreboardObjective", at = @At(value = "INVOKE", target = "Lnet/minecraft/scoreboard/Team;decorateName(Lnet/minecraft/scoreboard/AbstractTeam;Ljava/lang/String;)Ljava/lang/String;"))
	private String snowball$nick(String line) {
		return ModuleRegistry.NICK_HIDER != null ? ModuleRegistry.NICK_HIDER.sidebar(line) : line;
	}
}
