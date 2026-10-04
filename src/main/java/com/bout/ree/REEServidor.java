package com.bout.ree;

import com.bout.ree.red.AjustesPayload;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lado servidor: quién tiene REE y a cuántos chunks quiere ver jugadores.
 * Config en config/ree-servidor.properties (la decide el dueño del server / el host del LAN).
 */
public final class REEServidor {
	/** Si es false, el servidor se comporta 100% vanilla aunque los clientes tengan REE. */
	public static boolean activo = true;
	/** Tope que pone el servidor. Cada cliente elige hasta este número. */
	public static int chunksMaximos = 64;
	public static final int TOPE_ABSOLUTO = 256;

	/** UUID del jugador que mira -> chunks pedidos (solo jugadores con REE instalado y activado). */
	private static final Map<UUID, Integer> PEDIDOS = new ConcurrentHashMap<>();

	private REEServidor() {}

	static void recibirAjustes(ServerPlayer jugador, AjustesPayload p) {
		if (p.activo() && p.chunks() > 0) {
			int chunks = Math.min(Math.min(p.chunks(), chunksMaximos), TOPE_ABSOLUTO);
			PEDIDOS.put(jugador.getUUID(), chunks);
			REE.LOG.info("REE: {} ve jugadores hasta {} chunks", jugador.getName().getString(), chunks);
		} else {
			PEDIDOS.remove(jugador.getUUID());
		}
	}

	static void olvidar(ServerPlayer jugador) {
		PEDIDOS.remove(jugador.getUUID());
	}

	/**
	 * Rango extra en bloques con el que {@code quienMira} puede seguir a {@code objetivo}.
	 * Devuelve 0 si no corresponde (no es un jugador, el que mira no tiene REE, server apagado...),
	 * y en ese caso todo sigue exactamente como en vanilla.
	 */
	public static int rangoBloques(ServerPlayer quienMira, Entity objetivo) {
		if (!activo || !(objetivo instanceof ServerPlayer)) return 0;
		Integer chunks = PEDIDOS.get(quienMira.getUUID());
		if (chunks == null || chunks <= 0) return 0;
		return Math.min(chunks, chunksMaximos) * 16;
	}

	static void cargarConfig() {
		Path archivo = FabricLoader.getInstance().getConfigDir().resolve("ree-servidor.properties");
		Properties p = new Properties();
		try {
			if (Files.exists(archivo)) {
				try (Reader r = Files.newBufferedReader(archivo, StandardCharsets.UTF_8)) { p.load(r); }
			}
			activo = !"no".equalsIgnoreCase(p.getProperty("activo", "si").trim());
			chunksMaximos = Math.max(2, Math.min(TOPE_ABSOLUTO, Integer.parseInt(p.getProperty("chunks_maximos", "64").trim())));
			p.setProperty("activo", activo ? "si" : "no");
			p.setProperty("chunks_maximos", String.valueOf(chunksMaximos));
			try (Writer w = Files.newBufferedWriter(archivo, StandardCharsets.UTF_8)) {
				p.store(w, "REE (servidor) - activo: si/no | chunks_maximos: hasta cuantos chunks puede pedir cada jugador (2-256)");
			}
		} catch (Exception e) {
			REE.LOG.warn("REE: no se pudo leer ree-servidor.properties, uso valores por defecto", e);
		}
	}
}
