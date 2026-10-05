package com.bout.ree.cliente;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.waypoints.TrackedWaypoint;
import net.minecraft.world.waypoints.Waypoint;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modo universal: lee los datos de la BARRA LOCALIZADORA vanilla (cualquier servidor 26.2 con la regla
 * locatorBar prendida, que viene así por defecto).
 *
 * Qué manda vanilla según la distancia (WaypointTransmitter, código del servidor 26.2):
 *  - dentro de tu distancia de vista: posición exacta (bloque)
 *  - fuera de tu vista y hasta 332 bloques: el CHUNK donde está (precisión ~16 bloques)
 *  - más de 332 bloques: solo la DIRECCIÓN, pero muy fina: se actualiza cada vez que cambia medio grado
 *
 * TRIANGULACIÓN (lo nuevo): con "solo dirección" vanilla no da distancia, pero:
 *  1. Cuando vos te movés, la dirección hacia el otro cambia. Cada dirección es una línea que pasa por
 *     donde estabas; donde se cruzan las líneas está el jugador (como los barcos con la brújula).
 *  2. Cuando el servidor pasa de "chunk" a "solo dirección" es porque el jugador cruzó EXACTO los 332 bloques:
 *     en ese instante sabemos dónde está. Eso es un ancla que después se va desgastando con el tiempo.
 * Todo se combina con mínimos cuadrados: cada dato pesa según qué tan viejo es (el otro se puede mover).
 */
public final class Radar {
	public static final int EXACTO = 1, CHUNK = 2, DIRECCION = 3;
	private static final double ANILLO = 332.0;

	public record Punto(int tipo, int x, int y, int z, float anguloGrados) {}

	/** Posición estimada de un jugador que está a más de 332 bloques. error = ± bloques sobre la distancia. */
	public record Estimacion(double x, double z, double distancia, double error, long ms) {}

	static final Map<UUID, Punto> PUNTOS = new ConcurrentHashMap<>();
	private static final Map<UUID, Pista> PISTAS = new ConcurrentHashMap<>();

	/** Historial de un jugador lejano para triangular. */
	private static final class Pista {
		final ArrayDeque<double[]> obs = new ArrayDeque<>(); // {ms, miX, miZ, ánguloRad}
		double anclaX, anclaZ;
		long anclaMs; // 0 = sin ancla
		int ultimoTipo;
		volatile Estimacion est;
	}

	private Radar() {}

	/** Lo llama WaypointPacketMixin cada vez que llega un paquete de la barra localizadora (hilo del juego). */
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
			long ahora = System.currentTimeMillis();
			Pista p = PISTAS.computeIfAbsent(id.get(), k -> new Pista());
			Player yo = Minecraft.getInstance().player;
			switch (tipo) {
				case 1 -> {
					int x = b.readVarInt(), y = b.readVarInt(), z = b.readVarInt();
					PUNTOS.put(id.get(), new Punto(EXACTO, x, y, z, 0f));
					anclar(p, x + 0.5, z + 0.5, ahora);
				}
				case 2 -> {
					int cx = b.readVarInt(), cz = b.readVarInt();
					PUNTOS.put(id.get(), new Punto(CHUNK, cx * 16 + 8, 0, cz * 16 + 8, 0f));
					anclar(p, cx * 16 + 8, cz * 16 + 8, ahora);
				}
				case 3 -> {
					float rad = b.readFloat();
					float grados = (float) Math.toDegrees(rad);
					PUNTOS.put(id.get(), new Punto(DIRECCION, 0, 0, 0, grados));
					if (yo != null) {
						// Recién cruzó los 332 bloques: está justo en el anillo, en esta dirección
						if (p.ultimoTipo == CHUNK || p.ultimoTipo == EXACTO) {
							p.anclaX = yo.getX() - Math.sin(rad) * ANILLO;
							p.anclaZ = yo.getZ() + Math.cos(rad) * ANILLO;
							p.anclaMs = ahora;
						}
						p.obs.addLast(new double[] { ahora, yo.getX(), yo.getZ(), rad });
						while (p.obs.size() > 60 || (!p.obs.isEmpty() && ahora - p.obs.peekFirst()[0] > 120000)) p.obs.removeFirst();
						p.est = estimar(p, ahora, yo.getX(), yo.getZ());
					}
				}
				default -> {
					PUNTOS.remove(id.get());
					PISTAS.remove(id.get());
					return;
				}
			}
			p.ultimoTipo = tipo == 1 ? EXACTO : tipo == 2 ? CHUNK : DIRECCION;
		} catch (Exception ignorado) {
			// paquete con algo raro: se ignora, no rompe nada
		} finally {
			raw.release();
		}
	}

	private static void anclar(Pista p, double x, double z, long ms) {
		p.anclaX = x;
		p.anclaZ = z;
		p.anclaMs = ms;
		p.obs.clear();
		p.est = null;
	}

	/** Estimación vigente (o null si no hay datos suficientes o ya es vieja). */
	public static Estimacion estimacion(UUID id) {
		Pista p = PISTAS.get(id);
		if (p == null) return null;
		Estimacion e = p.est;
		if (e == null) return null;
		// si no llegan datos nuevos, la estimación "envejece": el otro se pudo mover ~4 bloques por segundo
		double edad = (System.currentTimeMillis() - e.ms()) / 1000.0;
		double error = e.error() + 4 * edad;
		if (error > e.distancia() * 0.35) return null;
		return e;
	}

	/**
	 * Mínimos cuadrados: busca el punto T que mejor cae sobre todas las líneas de dirección
	 * (y cerca del ancla, si hay). Cada dato pesa 1/σ², con σ que crece con la edad del dato
	 * (suponemos que el otro se puede mover ~4 bloques por segundo).
	 */
	private static Estimacion estimar(Pista p, long ahora, double miX, double miZ) {
		double a11 = 0, a12 = 0, a22 = 0, b1 = 0, b2 = 0;
		for (double[] o : p.obs) {
			double edad = (ahora - o[0]) / 1000.0;
			double sigma = 3 + 4 * edad;
			double w = 1 / (sigma * sigma);
			double th = o[3];
			double nx = Math.cos(th), nz = Math.sin(th); // normal a la línea de dirección (-sin θ, cos θ)
			double c = nx * o[1] + nz * o[2];
			a11 += w * nx * nx; a12 += w * nx * nz; a22 += w * nz * nz;
			b1 += w * nx * c; b2 += w * nz * c;
		}
		if (p.anclaMs > 0) {
			double edad = (ahora - p.anclaMs) / 1000.0;
			if (edad < 180) {
				double sigma = 12 + 4 * edad;
				double w = 1 / (sigma * sigma);
				a11 += w; a22 += w;
				b1 += w * p.anclaX; b2 += w * p.anclaZ;
			}
		}
		double det = a11 * a22 - a12 * a12;
		if (det <= 1e-14 || p.obs.isEmpty()) return null;
		double tx = (a22 * b1 - a12 * b2) / det, tz = (a11 * b2 - a12 * b1) / det;

		// distancia sobre la dirección más reciente (la dirección es exacta; lo incierto es la distancia)
		double th = p.obs.peekLast()[3];
		double dx = -Math.sin(th), dz = Math.cos(th);
		double sobreDir = (tx - miX) * dx + (tz - miZ) * dz;
		double varDir = (a22 * dx * dx - 2 * a12 * dx * dz + a11 * dz * dz) / det;
		double error = Math.sqrt(Math.max(varDir, 0));

		// tiene que estar adelante, más allá del anillo, y con un error razonable
		if (sobreDir < ANILLO * 0.85 || error > sobreDir * 0.35 || error > 900) return null;
		return new Estimacion(miX + dx * sobreDir, miZ + dz * sobreDir, sobreDir, error, ahora);
	}

	static void limpiar() {
		PUNTOS.clear();
		PISTAS.clear();
	}
}
