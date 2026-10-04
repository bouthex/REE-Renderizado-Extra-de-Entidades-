package com.bout.ree.mixin.cliente;

import com.bout.ree.cliente.ConfigCliente;
import com.bout.ree.cliente.REECliente;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Más allá de tu distancia de renderizado está la niebla, que tapa el modelo del jugador.
 * El contorno (el mismo efecto que "Brillo") no lo afecta la niebla, así que se ve la silueta moviéndose.
 * Es solo visual y solo en tu pantalla: el jugador no recibe ningún efecto.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "shouldEntityAppearGlowing", at = @At("HEAD"), cancellable = true)
	private void ree$contornoLejano(Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if (ConfigCliente.contorno && REECliente.esLejano(entity)) cir.setReturnValue(true);
	}
}
