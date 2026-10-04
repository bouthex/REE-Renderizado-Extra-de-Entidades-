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
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lado cliente de REE.
 *
 * Cada tick arma la lista de "jugadores lejanos": los que están fuera de tus chunks cargados
 * o más allá de tu distancia de renderizado. Los mixins de cliente.mixin consultan esa lista para:
 *  - no descartarlos por estar en un chunk que tu cliente no tiene (LevelRendererMixin)
 *  - no descartarlos por distancia (EntityRendererMixin / EntityMixin)
 *  - darles contorno brillante para que se vean a través de la niebla (MinecraftMixin)
 *
 * El movimiento lo hace el propio Minecraft: los jugadores son "always ticking", así que su
 * interpolación de posición, caminar, saltar, agacharse, nadar, etc. funcionan aunque su chunk no exista
 * en tu cliente. REE no inventa ni predice nada: lo que ves es lo que manda el servidor.
 */
public class REECliente implements ClientModInitializer {
	public record Lejano(String nombre, double distancia, float angulo) {}

	private static volatile Set<Integer> lejanos = Set.of();
	private static volatile Set<Long> secciones = Set.of();
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
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> enviarAjustes());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			servidorTieneREE = false;
			servidorActivo = false;
			servidorMax = 0;
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

	public static boolean esLejano(Entity e) {
		return e instanceof Player && lejanos.contains(e.getId());
	}

	/** ¿Hay algún jugador lejano en la sección (16x16x16) de este bloque o pegado a ella? */
	public static boolean hayLejanoEn(int x, int y, int z) {
		Set<Long> s = secciones;
		return !s.isEmpty() && s.contains(clave(x >> 4, y >> 4, z >> 4));
	}

	private static long clave(int sx, int sy, int sz) {
		return ((long) (sx & 0x3FFFFF) << 42) | ((long) (sz & 0x3FFFFF) << 20) | (sy & 0xFFFFF);
	}

	private static void limpiar() {
		lejanos = Set.of();
		secciones = Set.of();
		lista = List.of();
	}

	// ------------------------------------------------------------------ tick

	private static void actualizar(Minecraft mc) {
		if (mc.level == null || mc.player == null || !ConfigCliente.activo) {
			if (!lejanos.isEmpty() || !lista.isEmpty()) limpiar();
			return;
		}
		int rd = mc.options.renderDistance().get();
		double cerca = Math.max(2, rd - 1) * 16.0;
		double tope = ConfigCliente.chunks * 16.0 + 48.0;
		Player yo = mc.player;

		Set<Integer> ids = new HashSet<>();
		Set<Long> secs = new HashSet<>();
		List<Lejano> infos = new ArrayList<>();

		for (Player p : mc.level.players()) {
			if (p == yo || p.isRemoved()) continue;
			double dx = p.getX() - yo.getX(), dz = p.getZ() - yo.getZ();
			double d = Math.sqrt(dx * dx + dz * dz);
			int bx = (int) Math.floor(p.getX()), by = (int) Math.floor(p.getY()), bz = (int) Math.floor(p.getZ());
			boolean sinChunk = !mc.level.hasChunk(bx >> 4, bz >> 4);
			if (!sinChunk && d <= cerca) continue; // está cerca: vanilla se encarga
			if (d > tope) continue;

			ids.add(p.getId());
			int sx = bx >> 4, sy = by >> 4, sz = bz >> 4;
			// la sección del jugador y las vecinas (cubre la cabeza y el paso de una sección a otra entre ticks)
			for (int ox = -1; ox <= 1; ox++)
				for (int oy = -1; oy <= 1; oy++)
					for (int oz = -1; oz <= 1; oz++)
						secs.add(clave(sx + ox, sy + oy, sz + oz));

			float yawObjetivo = (float) Math.toDegrees(Math.atan2(-dx, dz));
			float relativo = ((yawObjetivo - yo.getYRot()) % 360f + 540f) % 360f - 180f;
			infos.add(new Lejano(p.getName().getString(), d, relativo));
		}
		infos.sort(Comparator.comparingDouble(Lejano::distancia));

		lejanos = ids;
		secciones = secs;
		lista = infos;
	}

	// ------------------------------------------------------------------ HUD

	private static void dibujarHud(GuiGraphicsExtractor g, DeltaTracker dt) {
		List<Lejano> l = lista;
		if (!ConfigCliente.hud || l.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		Font font = mc.font;
		int w = mc.getWindow().getGuiScaledWidth();

		int filas = Math.min(6, l.size());
		List<String> textos = new ArrayList<>();
		int ancho = font.width("REE · lejos");
		for (int i = 0; i < filas; i++) {
			Lejano j = l.get(i);
			String flecha = FLECHAS[Math.floorMod(Math.round(j.angulo() / 45f), 8)];
			String t = flecha + " " + j.nombre() + "  " + Math.round(j.distancia()) + "m";
			textos.add(t);
			ancho = Math.max(ancho, font.width(t));
		}
		if (l.size() > filas) textos.add("+" + (l.size() - filas) + " más");

		int x1 = w - 4, x0 = x1 - ancho - 10, y0 = 4;
		int alto = 14 + textos.size() * 10 + 2;
		g.fill(x0, y0, x1, y0 + alto, 0x88000000);
		g.fill(x0, y0, x0 + 2, y0 + alto, 0xFF4FC3F7);
		g.text(font, "REE · lejos", x0 + 6, y0 + 3, 0xFF4FC3F7, true);
		for (int i = 0; i < textos.size(); i++) {
			g.text(font, textos.get(i), x0 + 6, y0 + 15 + i * 10, 0xFFFFFFFF, true);
		}
	}
}
