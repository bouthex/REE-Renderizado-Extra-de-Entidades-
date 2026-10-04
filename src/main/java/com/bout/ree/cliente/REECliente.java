package com.bout.ree.cliente;

import com.bout.ree.red.AjustesPayload;
import com.bout.ree.red.ServidorPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lado cliente de REE. Funciona en dos niveles:
 *
 * MODO UNIVERSAL (cualquier servidor, aunque no tenga REE):
 *  - Dibuja a los jugadores que el servidor YA te manda en todo su rango. Vanilla los esconde a ~64 bloques
 *    aunque los tenga; REE no. Con tu distancia de renderizado al máximo que permita el server, ves cuerpos
 *    reales moviéndose hasta ese límite.
 *  - Más allá, usa la barra localizadora vanilla (Radar): nombre, distancia aproximada y marcador en pantalla.
 *
 * MODO COMPLETO (servidor o host de LAN con REE): el servidor manda jugadores hasta los chunks que elijas,
 * y se ven caminando aunque estén a cientos de bloques, sin cargar chunks.
 */
public class REECliente implements ClientModInitializer {
	/** precision: Radar.EXACTO, Radar.CHUNK o Radar.DIRECCION. */
	public record Lejano(String nombre, double distancia, float angulo, int precision) {}

	private static volatile Set<Integer> sinCorte = Set.of();   // se dibujan sin el corte de distancia vanilla
	private static volatile Set<Integer> conContorno = Set.of(); // contorno brillante (zona de niebla o sin chunk)
	private static volatile Set<Long> secciones = Set.of();      // secciones de chunk con jugadores sin chunk cargado
	private static volatile List<Lejano> lista = List.of();

	/** Lo que nos dijo el servidor al entrar (si no dijo nada, no tiene REE). */
	public static boolean servidorTieneREE = false;
	public static boolean servidorActivo = false;
	public static int servidorMax = 0;

	private static final String[] FLECHAS = { "↑", "↗", "→", "↘", "↓", "↙", "←", "↖" };

	@Override
	public void onInitializeClient() {
		ConfigCliente.cargar();

		ClientPlayNetworking.registerGlobalReceiver(ServidorPayload.TYPE, (payload, context) -> {
			servidorTieneREE = true;
			servidorActivo = payload.activo();
			servidorMax = payload.chunksMaximos();
			enviarAjustes();
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			Radar.limpiar();
			enviarAjustes();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			servidorTieneREE = false;
			servidorActivo = false;
			servidorMax = 0;
			Radar.limpiar();
			limpiar();
		});

		ClientTickEvents.END_CLIENT_TICK.register(REECliente::actualizar);
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("ree", "lista"), REECliente::dibujarHud);
	}

	/** Le manda al servidor nuestras opciones (si el servidor tiene REE; si no, no hace nada). */
	public static void enviarAjustes() {
		try {
			if (ClientPlayNetworking.canSend(AjustesPayload.TYPE)) {
				ClientPlayNetworking.send(new AjustesPayload(ConfigCliente.activo, ConfigCliente.chunks));
			}
		} catch (IllegalStateException ignorado) {
			// no estamos conectados a ningún mundo
		}
	}

	// ------------------------------------------------------------------ consultas para los mixins

	/** Jugador que se dibuja sin el corte de distancia vanilla. */
	public static boolean esLejano(Entity e) {
		return e instanceof Player && sinCorte.contains(e.getId());
	}

	public static boolean llevaContorno(Entity e) {
		return e instanceof Player && conContorno.contains(e.getId());
	}

	/** ¿Hay algún jugador sin chunk cargado en la sección (16x16x16) de este bloque o pegado a ella? */
	public static boolean hayLejanoEn(int x, int y, int z) {
		Set<Long> s = secciones;
		return !s.isEmpty() && s.contains(clave(x >> 4, y >> 4, z >> 4));
	}

	private static long clave(int sx, int sy, int sz) {
		return ((long) (sx & 0x3FFFFF) << 42) | ((long) (sz & 0x3FFFFF) << 20) | (sy & 0xFFFFF);
	}

	private static void limpiar() {
		sinCorte = Set.of();
		conContorno = Set.of();
		secciones = Set.of();
		lista = List.of();
	}

	private static float relativo(float yawObjetivo, float miYaw) {
		return ((yawObjetivo - miYaw) % 360f + 540f) % 360f - 180f;
	}

	// ------------------------------------------------------------------ tick

	private static void actualizar(Minecraft mc) {
		if (mc.level == null || mc.player == null || !ConfigCliente.activo) {
			if (!sinCorte.isEmpty() || !lista.isEmpty()) limpiar();
			return;
		}
		int rd = mc.options.renderDistance().get();
		double niebla = Math.max(2, rd - 2) * 16.0;
		double tope = Math.max(ConfigCliente.chunks, rd) * 16.0 + 48.0;
		Player yo = mc.player;

		Set<Integer> corte = new HashSet<>();
		Set<Integer> contorno = new HashSet<>();
		Set<Long> secs = new HashSet<>();
		Set<UUID> vistos = new HashSet<>();
		List<Lejano> infos = new ArrayList<>();

		// 1) Jugadores que el servidor nos manda de verdad (cuerpo real, posición exacta)
		for (Player p : mc.level.players()) {
			if (p == yo || p.isRemoved()) continue;
			vistos.add(p.getUUID());
			double dx = p.getX() - yo.getX(), dz = p.getZ() - yo.getZ();
			double d = Math.sqrt(dx * dx + dz * dz);
			if (d > tope) continue;
			int bx = (int) Math.floor(p.getX()), by = (int) Math.floor(p.getY()), bz = (int) Math.floor(p.getZ());
			boolean sinChunk = !mc.level.hasChunk(bx >> 4, bz >> 4);

			if (d > 32 || sinChunk) corte.add(p.getId());
			if (sinChunk || d > niebla) contorno.add(p.getId());
			if (sinChunk) {
				int sx = bx >> 4, sy = by >> 4, sz = bz >> 4;
				for (int ox = -1; ox <= 1; ox++)
					for (int oy = -1; oy <= 1; oy++)
						for (int oz = -1; oz <= 1; oz++)
							secs.add(clave(sx + ox, sy + oy, sz + oz));
			}
			if (d > 64 || sinChunk) {
				float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
				infos.add(new Lejano(p.getName().getString(), d, relativo(yaw, yo.getYRot()), Radar.EXACTO));
			}
		}

		// 2) Radar: los que el servidor NO nos manda, pero aparecen en la barra localizadora
		if (ConfigCliente.radar && mc.getConnection() != null) {
			for (Map.Entry<UUID, Radar.Punto> e : Radar.PUNTOS.entrySet()) {
				UUID id = e.getKey();
				if (vistos.contains(id) || id.equals(yo.getUUID())) continue;
				PlayerInfo info = mc.getConnection().getPlayerInfo(id);
				if (info == null) continue; // no es un jugador conectado
				String nombre = info.getProfile().name();
				Radar.Punto pt = e.getValue();
				if (pt.tipo() == Radar.DIRECCION) {
					infos.add(new Lejano(nombre, Double.MAX_VALUE, relativo(pt.anguloGrados(), yo.getYRot()), Radar.DIRECCION));
				} else {
					double dx = pt.x() + 0.5 - yo.getX(), dz = pt.z() + 0.5 - yo.getZ();
					float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
					infos.add(new Lejano(nombre, Math.sqrt(dx * dx + dz * dz), relativo(yaw, yo.getYRot()), pt.tipo()));
				}
			}
		}
		infos.sort(Comparator.comparingDouble(Lejano::distancia));

		sinCorte = corte;
		conContorno = contorno;
		secciones = secs;
		lista = infos;
	}

	// ------------------------------------------------------------------ HUD

	private static String distanciaTexto(Lejano j) {
		return switch (j.precision()) {
			case Radar.DIRECCION -> "+330m";
			case Radar.CHUNK -> "~" + Math.round(j.distancia()) + "m";
			default -> Math.round(j.distancia()) + "m";
		};
	}

	private static int color(Lejano j) {
		return switch (j.precision()) {
			case Radar.DIRECCION -> 0xFFFFA726; // naranja: solo dirección
			case Radar.CHUNK -> 0xFFFFEE58;     // amarillo: aproximado
			default -> 0xFF4FC3F7;              // celeste: exacto
		};
	}

	private static void dibujarHud(GuiGraphicsExtractor g, DeltaTracker dt) {
		List<Lejano> l = lista;
		if (l.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		if (ConfigCliente.marcadores) dibujarMarcadores(g, mc, l);
		if (ConfigCliente.hud) dibujarLista(g, mc, l);
	}

	/** Marcadores sobre el horizonte, en la dirección real de cada jugador (como una brújula). */
	private static void dibujarMarcadores(GuiGraphicsExtractor g, Minecraft mc, List<Lejano> l) {
		Font font = mc.font;
		int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
		double fovV = Math.toRadians(mc.options.fov().get());
		double mitadH = Math.atan(Math.tan(fovV / 2) * w / (double) h);
		int y = h / 2 - 34;
		for (Lejano j : l) {
			double rel = Math.toRadians(j.angulo());
			if (Math.abs(rel) >= mitadH * 0.98) continue;
			int x = (int) Math.round(w / 2.0 + Math.tan(rel) / Math.tan(mitadH) * (w / 2.0));
			int c = color(j);
			g.fill(x - 2, y - 2, x + 3, y + 3, 0xFF000000);
			g.fill(x - 1, y - 1, x + 2, y + 2, c);
			String t1 = j.nombre(), t2 = distanciaTexto(j);
			g.text(font, t1, x - font.width(t1) / 2, y - 22, 0xFFFFFFFF, true);
			g.text(font, t2, x - font.width(t2) / 2, y - 12, c, true);
		}
	}

	private static void dibujarLista(GuiGraphicsExtractor g, Minecraft mc, List<Lejano> l) {
		Font font = mc.font;
		int w = mc.getWindow().getGuiScaledWidth();
		int filas = Math.min(6, l.size());
		List<String> textos = new ArrayList<>();
		List<Integer> colores = new ArrayList<>();
		String titulo = servidorTieneREE && servidorActivo ? "REE · lejos" : "REE · radar";
		int ancho = font.width(titulo);
		for (int i = 0; i < filas; i++) {
			Lejano j = l.get(i);
			String flecha = FLECHAS[Math.floorMod(Math.round(j.angulo() / 45f), 8)];
			String t = flecha + " " + j.nombre() + "  " + distanciaTexto(j);
			textos.add(t);
			colores.add(color(j));
			ancho = Math.max(ancho, font.width(t));
		}
		if (l.size() > filas) { textos.add("+" + (l.size() - filas) + " más"); colores.add(0xFFBDBDBD); }

		int x1 = w - 4, x0 = x1 - ancho - 10, y0 = 4;
		int alto = 14 + textos.size() * 10 + 2;
		g.fill(x0, y0, x1, y0 + alto, 0x88000000);
		g.fill(x0, y0, x0 + 2, y0 + alto, 0xFF4FC3F7);
		g.text(font, titulo, x0 + 6, y0 + 3, 0xFF4FC3F7, true);
		for (int i = 0; i < textos.size(); i++) {
			g.text(font, textos.get(i), x0 + 6, y0 + 15 + i * 10, colores.get(i), true);
		}
	}
}
