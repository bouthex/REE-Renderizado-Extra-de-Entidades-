package com.bout.ree.mixin.cliente;

import com.bout.ree.cliente.REECliente;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla corta el dibujo de entidades a ~64 bloques × "Distancia de entidades". A los lejanos no. */
@Mixin(Entity.class)
public abstract class EntityMixin {
	@Inject(method = "shouldRenderAtSqrDistance", at = @At("HEAD"), cancellable = true)
	private void ree$sinCorteDistancia(double distancia, CallbackInfoReturnable<Boolean> cir) {
		if (REECliente.esLejano((Entity) (Object) this)) cir.setReturnValue(true);
	}
}
