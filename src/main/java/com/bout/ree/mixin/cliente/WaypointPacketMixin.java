package com.bout.ree.mixin.cliente;

import com.bout.ree.cliente.Radar;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.world.waypoints.TrackedWaypointManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Copia los datos de la barra localizadora vanilla para el radar de REE (sin cambiar nada de vanilla). */
@Mixin(ClientboundTrackedWaypointPacket.class)
public abstract class WaypointPacketMixin {
	@Inject(method = "apply", at = @At("HEAD"))
	private void ree$radar(TrackedWaypointManager manager, CallbackInfo ci) {
		Radar.recibir(((ClientboundTrackedWaypointPacket) (Object) this).waypoint());
	}
}
