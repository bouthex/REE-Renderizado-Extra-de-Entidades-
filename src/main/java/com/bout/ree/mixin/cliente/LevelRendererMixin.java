package com.bout.ree.mixin.cliente;

import com.bout.ree.cliente.REECliente;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla solo dibuja una entidad si la sección de chunk donde está ya fue compilada en tu cliente.
 * Un jugador a 800 bloques está en un chunk que tu cliente ni tiene, así que nunca se dibujaría.
 * Si en esa sección hay un jugador lejano, decimos que sí. (Sodium reemplaza este mismo método;
 * esta inyección se aplica igual encima.)
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
	@Inject(method = "isSectionCompiledAndVisible", at = @At("HEAD"), cancellable = true)
	private void ree$seccionLejana(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (REECliente.hayLejanoEn(pos.getX(), pos.getY(), pos.getZ())) cir.setReturnValue(true);
	}
}
