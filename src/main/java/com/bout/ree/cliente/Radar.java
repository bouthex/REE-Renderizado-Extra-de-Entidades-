package com.bout.ree.cliente;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.waypoints.TrackedWaypoint;
import net.minecraft.world.waypoints.Waypoint;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modo universal: lee los datos de la BARRA LOCALIZADORA vanilla, que cualquier servidor 26.2 manda
 * (si no apagaron la regla locatorBar), aunque no tenga REE.
 *
 * Qué manda vanilla según la distancia (WaypointTransmitter, código del servidor 26.2):
 *  - dentro de tu distancia de vista: posición exacta (bloque)
 *  - fuera de tu vista y hasta 332 bloques: el CHUNK donde está (precisión ~16 bloques)
 *  - más de 332 bloques: solo la DIRECCIÓN (ángulo), sin distancia
 *
 * Vanilla solo lo usa para los puntitos de la barra. REE lo usa para nombre, distancia y marcador.
 */
public final class Radar {
	public static final int EXACTO = 1, CHUNK = 2, DIRECCION = 3;

	public record Punto(int tipo, int x, int y, int z, float anguloGrados) {}

	static final Map<UUID, Punto> PUNTOS = new ConcurrentHashMap<>();

	private Radar() {}

	/** Lo llama WaypointPacketMixin cada vez que llega un paquete de la barra localizadora. */
	public static void recibir(TrackedWaypoint wp) {
		Optional<UUID> id = wp.id().left();
		if (id.isEmpty()) return;
		ByteBuf raw = Unpooled.buffer();
		try {
			// Se serializa con el formato vanilla y se lee de vuelta: así no dependemos de clases privadas
			wp.write(raw);
			FriendlyByteBuf b = new FriendlyByteBuf(raw);
			b.readEither(UUIDUtil.STREAM_CODEC, FriendlyByteBuf::readUtf);
			Waypoint.Icon.STREAM_CODEC.decode(b);
			int tipo = b.readVarInt(); // 0 vacío (dejar de seguir), 1 bloque, 2 chunk, 3 ángulo
			switch (tipo) {
				case 1 -> PUNTOS.put(id.get(), new Punto(EXACTO, b.readVarInt(), b.readVarInt(), b.readVarInt(), 0f));
				case 2 -> {
					int cx = b.readVarInt(), cz = b.readVarInt();
					PUNTOS.put(id.get(), new Punto(CHUNK, cx * 16 + 8, 0, cz * 16 + 8, 0f));
				}
				case 3 -> PUNTOS.put(id.get(), new Punto(DIRECCION, 0, 0, 0, (float) Math.toDegrees(b.readFloat())));
				default -> PUNTOS.remove(id.get());
			}
		} catch (Exception ignorado) {
			// paquete con algo raro: se ignora, no rompe nada
		} finally {
			raw.release();
		}
	}

	static void limpiar() {
		PUNTOS.clear();
	}
}
