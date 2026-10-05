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
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.HashMap;
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
	/** Tipos de objetivo: de dónde sale el dato y qué tan preciso es. */
	public static final int EXACTO = 1, CHUNK = 2, DIRECCION = 3, GRUPO = 4;

	/** Un jugador lejano para mostrar. (x, y, z) es dónde dibujar el marcador; para DIRECCION es un punto lejano en esa dirección. */
	public record Lejano(UUID uuid, String nombre, double x, double y, double z, double distancia, float angulo, int tipo) {}

	private static volatile Set<Integer> sinCorte = Set.of();   // se dibujan sin el corte de distancia vanilla
	private static volatile Set<Integer> conContorno = Set.of(); // contorno brillante (zona de niebla o sin chunk)
	private static volatile Set<Long> secciones = Set.of();      // secciones de chunk con jugadores sin chunk cargado
	private static volatile List<Lejano> lista = List.of();

	/** Lo que nos dijo el servidor al entrar (si no dijo nada, no tiene REE). */
	public static boolean servidorTieneREE = false;
	public static boolean servidorActivo = false;
	public static int servidorMax = 0;

	private static final String[] FLECHAS = { "↑", "↗", "→", "↘", "↓", "↙", "←", "↖" };
	/** Posición suavizada de cada marcador (solo hilo de render). */
	private static final Map<UUID, double[]> SUAVE = new HashMap<>();
	private static long ultimoFrame = System.nanoTime();

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

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			Grupo.tick(mc);
			actualizar(mc);
		});
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
		String miDim = mc.level.dimension().identifier().toString();

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
				infos.add(new Lejano(p.getUUID(), p.getName().getString(), p.getX(), p.getY() + 2.2, p.getZ(), d,
						relativo(yawHacia(dx, dz), yo.getYRot()), EXACTO));
			}
		}

		// 2) Grupo REE: amigos en este mismo servidor y dimensión
		if (ConfigCliente.grupo && mc.getConnection() != null) {
			for (Grupo.Amigo a : Grupo.AMIGOS.values()) {
				if (vistos.contains(a.uuid()) || a.uuid().equals(yo.getUUID())) continue;
				if (!a.dim().equals(miDim) || !ConfigCliente.esAmigo(a.nombre())) continue;
				if (mc.getConnection().getPlayerInfo(a.uuid()) == null) continue; // no está en tu mismo servidor
				vistos.add(a.uuid());
				double dx = a.x() - yo.getX(), dz = a.z() - yo.getZ();
				infos.add(new Lejano(a.uuid(), a.nombre(), a.x(), a.y() + 2.2, a.z(), Math.sqrt(dx * dx + dz * dz),
						relativo(yawHacia(dx, dz), yo.getYRot()), GRUPO));
			}
		}

		// 3) Radar: barra localizadora vanilla, para los que no tenemos de otra forma
		if (ConfigCliente.radar && mc.getConnection() != null) {
			for (Map.Entry<UUID, Radar.Punto> e : Radar.PUNTOS.entrySet()) {
				UUID id = e.getKey();
				if (vistos.contains(id) || id.equals(yo.getUUID())) continue;
				PlayerInfo info = mc.getConnection().getPlayerInfo(id);
				if (info == null) continue; // no es un jugador conectado
				String nombre = info.getProfile().name();
				Radar.Punto pt = e.getValue();
				if (pt.tipo() == Radar.DIRECCION) {
					double a = Math.toRadians(pt.anguloGrados());
					infos.add(new Lejano(id, nombre, yo.getX() - Math.sin(a) * 2000, yo.getEyeY(), yo.getZ() + Math.cos(a) * 2000,
							Double.MAX_VALUE, relativo(pt.anguloGrados(), yo.getYRot()), DIRECCION));
				} else {
					double dx = pt.x() + 0.5 - yo.getX(), dz = pt.z() + 0.5 - yo.getZ();
					int tipo = pt.tipo() == Radar.CHUNK ? CHUNK : EXACTO;
					double y = pt.tipo() == Radar.CHUNK ? yo.getEyeY() : pt.y() + 2.2;
					infos.add(new Lejano(id, nombre, pt.x() + 0.5, y, pt.z() + 0.5, Math.sqrt(dx * dx + dz * dz),
							relativo(yawHacia(dx, dz), yo.getYRot()), tipo));
				}
			}
		}
		infos.sort(Comparator.comparingDouble(Lejano::distancia));

		sinCorte = corte;
		conContorno = contorno;
		secciones = secs;
		lista = infos;
	}

	private static float yawHacia(double dx, double dz) {
		return (float) Math.toDegrees(Math.atan2(-dx, dz));
	}

	// ------------------------------------------------------------------ HUD (diseño compacto)

	private static String distanciaTexto(Lejano j) {
		return switch (j.tipo()) {
			case DIRECCION -> "+330 m";
			case CHUNK -> "~" + Math.round(j.distancia()) + " m";
			default -> Math.round(j.distancia()) + " m";
		};
	}

	private static int color(Lejano j) {
		return switch (j.tipo()) {
			case GRUPO -> 0xFF7CE38B;     // verde: amigo del grupo
			case DIRECCION -> 0xFFFFB74D; // naranja: solo dirección
			case CHUNK -> 0xFFFFE082;     // amarillo: aproximado
			default -> 0xFF6FD3FF;        // celeste: exacto
		};
	}

	private static void dibujarHud(GuiGraphicsExtractor g, DeltaTracker dt) {
		List<Lejano> l = lista;
		if (l.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		long ahora = System.nanoTime();
		double seg = Math.min(0.25, (ahora - ultimoFrame) / 1e9);
		ultimoFrame = ahora;
		if (ConfigCliente.marcadores) dibujarMarcadores(g, mc, l, dt.getGameTimeDeltaPartialTick(false), seg);
		if (ConfigCliente.hud) dibujarLista(g, mc, l);
	}

	/** Texto chico centrado (escala < 1 para que no ocupe pantalla). */
	private static void textoChico(GuiGraphicsExtractor g, Font font, String t, float x, float y, float escala, int color) {
		Matrix3x2fStack m = g.pose();
		m.pushMatrix();
		m.translate(x, y);
		m.scale(escala, escala);
		g.text(font, t, -font.width(t) / 2, 0, color, true);
		m.popMatrix();
	}

	/**
	 * Marcadores proyectados en 3D: aparecen en pantalla justo donde está cada jugador (también en altura),
	 * como un waypoint. Si está fuera de la vista, queda pegado al borde con una flechita.
	 */
	private static void dibujarMarcadores(GuiGraphicsExtractor g, Minecraft mc, List<Lejano> l, float pt, double seg) {
		Font font = mc.font;
		Player yo = mc.player;
		int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
		Vec3 ojo = yo.getEyePosition(pt);
		double yaw = Math.toRadians(yo.getViewYRot(pt)), pitch = Math.toRadians(yo.getViewXRot(pt));
		// base de la cámara: adelante, derecha, arriba
		double fx = -Math.sin(yaw) * Math.cos(pitch), fy = -Math.sin(pitch), fz = Math.cos(yaw) * Math.cos(pitch);
		double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
		double ux = -rz * fy, uy = rz * fx - rx * fz, uz = rx * fy; // arriba = derecha × adelante
		double tanV = Math.tan(Math.toRadians(mc.options.fov().get()) / 2);
		double suavizado = Math.min(1, seg * 10);

		Set<UUID> usados = new HashSet<>();
		for (Lejano j : l) {
			usados.add(j.uuid());
			double[] p = SUAVE.computeIfAbsent(j.uuid(), k -> new double[] { j.x(), j.y(), j.z() });
			// movimiento suave entre actualizaciones (sin saltos); si es un salto enorme, se teletransporta
			if (Math.abs(p[0] - j.x()) + Math.abs(p[2] - j.z()) > 64) { p[0] = j.x(); p[1] = j.y(); p[2] = j.z(); }
			p[0] += (j.x() - p[0]) * suavizado;
			p[1] += (j.y() - p[1]) * suavizado;
			p[2] += (j.z() - p[2]) * suavizado;

			double dx = p[0] - ojo.x, dy = p[1] - ojo.y, dz = p[2] - ojo.z;
			double xc = dx * rx + dz * rz, yc = dx * ux + dy * uy + dz * uz, zc = dx * fx + dy * fy + dz * fz;
			int c = color(j);
			boolean borde = false;
			float sx, sy;
			if (zc > 0.1) {
				sx = (float) (w / 2.0 + (xc / (zc * tanV)) * (h / 2.0));
				sy = (float) (h / 2.0 - (yc / (zc * tanV)) * (h / 2.0));
				if (sx < 8 || sx > w - 8 || sy < 14 || sy > h - 30) borde = true;
			} else {
				sx = w / 2f; // detrás: se pega al costado hacia donde conviene girar
				sy = h / 2f;
				borde = true;
			}
			if (borde) {
				boolean derecha = zc > 0.1 ? sx > w / 2f : xc > 0;
				sx = derecha ? w - 10 : 10;
				sy = Math.max(20, Math.min(h - 40, sy));
				textoChico(g, font, derecha ? "›" : "‹", sx + (derecha ? 5 : -5), sy - 3, 1f, c);
			}
			// punto
			int ix = Math.round(sx), iy = Math.round(sy);
			g.fill(ix - 2, iy - 2, ix + 2, iy + 2, 0xCC000000);
			g.fill(ix - 1, iy - 1, ix + 1, iy + 1, c);
			// nombre y distancia, chiquitos, con fondo suave
			String nombre = j.nombre(), dist = distanciaTexto(j);
			float anchoTxt = Math.max(font.width(nombre) * 0.6f, font.width(dist) * 0.5f);
			g.fill(Math.round(sx - anchoTxt / 2 - 2), iy - 15, Math.round(sx + anchoTxt / 2 + 2), iy - 3, 0x66000000);
			textoChico(g, font, nombre, sx, iy - 14, 0.6f, 0xFFFFFFFF);
			textoChico(g, font, dist, sx, iy - 8, 0.5f, c);
		}
		SUAVE.keySet().retainAll(usados);
	}

	/** Lista compacta arriba a la derecha: punto de color, nombre, distancia y flecha. */
	private static void dibujarLista(GuiGraphicsExtractor g, Minecraft mc, List<Lejano> l) {
		Font font = mc.font;
		float e = 0.65f;
		int w = mc.getWindow().getGuiScaledWidth();
		int filas = Math.min(5, l.size());
		int anchoMax = 0;
		List<String> textos = new ArrayList<>();
		for (int i = 0; i < filas; i++) {
			Lejano j = l.get(i);
			String t = j.nombre() + " " + distanciaTexto(j) + " " + FLECHAS[Math.floorMod(Math.round(j.angulo() / 45f), 8)];
			textos.add(t);
			anchoMax = Math.max(anchoMax, font.width(t));
		}
		if (l.size() > filas) { textos.add("+" + (l.size() - filas)); }
		int ancho = Math.round(anchoMax * e) + 12;
		int fila = 7;
		int x0 = w - 3 - ancho, y0 = 3, alto = textos.size() * fila + 4;
		g.fill(x0, y0, x0 + ancho, y0 + alto, 0x55000000);

		Matrix3x2fStack m = g.pose();
		for (int i = 0; i < textos.size(); i++) {
			int yy = y0 + 2 + i * fila;
			int c = i < filas ? color(l.get(i)) : 0xFFBDBDBD;
			g.fill(x0 + 3, yy + 2, x0 + 6, yy + 5, c);
			m.pushMatrix();
			m.translate(x0 + 9, yy + 1);
			m.scale(e, e);
			g.text(font, textos.get(i), 0, 0, 0xFFEFEFEF, false);
			m.popMatrix();
		}
	}
}
