package com.bout.ree.cliente;

import com.bout.ree.REE;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Opciones del jugador (config/ree.properties). Se cambian desde Mod Menu. */
public final class ConfigCliente {
	/** Distancias que se pueden elegir en la pantalla, en chunks. */
	public static final int[] OPCIONES_CHUNKS = { 12, 16, 24, 32, 48, 64, 96, 128, 192, 256 };

	public static boolean activo = true;
	public static int chunks = 32;
	/** Contorno brillante en los jugadores lejanos (se ve a través de la niebla y de las montañas). */
	public static boolean contorno = true;
	/** Lista en pantalla con nombre, distancia y dirección de los jugadores lejanos. */
	public static boolean hud = true;
	/** Usar los datos de la barra localizadora vanilla (funciona en servidores sin REE). */
	public static boolean radar = true;
	/** Nombre y distancia flotando sobre el horizonte, en la dirección de cada jugador. */
	public static boolean marcadores = true;

	private ConfigCliente() {}

	private static Path archivo() {
		return FabricLoader.getInstance().getConfigDir().resolve("ree.properties");
	}

	static void cargar() {
		Properties p = new Properties();
		try {
			if (Files.exists(archivo())) {
				try (Reader r = Files.newBufferedReader(archivo(), StandardCharsets.UTF_8)) { p.load(r); }
			}
			activo = !"no".equalsIgnoreCase(p.getProperty("activo", "si").trim());
			chunks = Math.max(2, Math.min(256, Integer.parseInt(p.getProperty("chunks", "32").trim())));
			contorno = !"no".equalsIgnoreCase(p.getProperty("contorno", "si").trim());
			hud = !"no".equalsIgnoreCase(p.getProperty("hud", "si").trim());
			radar = !"no".equalsIgnoreCase(p.getProperty("radar", "si").trim());
			marcadores = !"no".equalsIgnoreCase(p.getProperty("marcadores", "si").trim());
		} catch (Exception e) {
			REE.LOG.warn("REE: no se pudo leer ree.properties, uso valores por defecto", e);
		}
		guardar();
	}

	public static void guardar() {
		Properties p = new Properties();
		p.setProperty("activo", activo ? "si" : "no");
		p.setProperty("chunks", String.valueOf(chunks));
		p.setProperty("contorno", contorno ? "si" : "no");
		p.setProperty("hud", hud ? "si" : "no");
		p.setProperty("radar", radar ? "si" : "no");
		p.setProperty("marcadores", marcadores ? "si" : "no");
		try (Writer w = Files.newBufferedWriter(archivo(), StandardCharsets.UTF_8)) {
			p.store(w, "REE (cliente) - se cambia mejor desde Mod Menu");
		} catch (Exception e) {
			REE.LOG.warn("REE: no se pudo guardar ree.properties", e);
		}
	}

	/** Siguiente / anterior opción de distancia. */
	static void moverChunks(int paso) {
		int i = 0;
		while (i < OPCIONES_CHUNKS.length - 1 && OPCIONES_CHUNKS[i] < chunks) i++;
		i = Math.floorMod(i + paso, OPCIONES_CHUNKS.length);
		chunks = OPCIONES_CHUNKS[i];
	}
}
