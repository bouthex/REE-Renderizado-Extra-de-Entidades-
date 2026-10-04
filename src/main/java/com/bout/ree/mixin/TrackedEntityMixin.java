package com.bout.ree.mixin;

import com.bout.ree.REEServidor;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * El corazón de REE en el servidor.
 *
 * En vanilla 26.2, ChunkMap.TrackedEntity#updatePlayer decide si un jugador "ve" una entidad con:
 *   distancia <= min(rangoDeLaEntidad, distanciaDeVistaDelJugador * 16)
 *   && entidad.broadcastToPlayer(jugador)
 *   && el chunk de la entidad está siendo enviado a ese jugador (isChunkTracked)
 *
 * Las dos trabas para ver lejos son el min(...) y el isChunkTracked. Acá las aflojamos SOLO cuando
 * la entidad es un jugador y quien mira tiene REE. broadcastToPlayer se respeta (espectadores, etc.).
 * Todo lo demás (mobs, ítems, jugadores sin el mod) queda idéntico a vanilla.
 */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class TrackedEntityMixin {
	@Shadow @Final private Entity entity;

	/** Rango: el mayor entre el vanilla y el que pidió el jugador con REE. */
	@WrapOperation(method = "updatePlayer", at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I"))
	private int ree$ampliarRango(int a, int b, Operation<Integer> original, ServerPlayer player) {
		int vanilla = original.call(a, b);
		return Math.max(vanilla, REEServidor.rangoBloques(player, this.entity));
	}

	/** Chunk: si es un jugador lejano y quien mira tiene REE, no hace falta que su chunk esté cargado. */
	@WrapOperation(method = "updatePlayer", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/server/level/ChunkMap;isChunkTracked(Lnet/minecraft/server/level/ServerPlayer;II)Z"))
	private boolean ree$sinChunk(ChunkMap mapa, ServerPlayer viewer, int x, int z, Operation<Boolean> original, ServerPlayer player) {
		return original.call(mapa, viewer, x, z) || REEServidor.rangoBloques(player, this.entity) > 0;
	}
}
