package com.bout.ree.mixin.cliente;

import com.bout.ree.cliente.REECliente;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Para los jugadores lejanos solo pedimos que estén dentro de la cámara (frustum).
 * Se saltea el corte por distancia de vanilla y el culling por chunks de Sodium/otros mods,
 * que los esconderían por estar en chunks que tu cliente no tiene.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void ree$jugadorLejano(Entity entity, Frustum frustum, double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir) {
		if (REECliente.esLejano(entity)) {
			cir.setReturnValue(frustum.isVisible(entity.getBoundingBox().inflate(1.0)));
		}
	}
}
