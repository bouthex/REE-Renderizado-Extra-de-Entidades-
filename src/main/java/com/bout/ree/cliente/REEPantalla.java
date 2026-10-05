package com.bout.ree.cliente;

import com.bout.ree.REE;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2fStack;

/** Configuración de REE (se abre desde Mod Menu). Se guarda en config/ree.properties. */
public class REEPantalla extends Screen {
	private final Screen anterior;
	private int x0, y0, ancho, alto;

	public REEPantalla(Screen anterior) {
		super(Component.literal("REE - Configuración"));
		this.anterior = anterior;
	}

	@Override
	protected void init() {
		ancho = Math.min(340, this.width - 20);
		int bh = 20, sep = 24;
		alto = 60 + 7 * sep + 34;
		x0 = (this.width - ancho) / 2;
		y0 = Math.max(6, (this.height - alto) / 2);
		int x = x0 + 10, w = ancho - 20;
		int y = y0 + 54;

		this.addRenderableWidget(Button.builder(si("REE activado", ConfigCliente.activo), b -> {
			ConfigCliente.activo = !ConfigCliente.activo;
			b.setMessage(si("REE activado", ConfigCliente.activo));
		}).bounds(x, y, w, bh).build());
		y += sep;

		// Distancia: [-] [ valor ] [+]
		Button valor = Button.builder(distancia(), b -> {
			ConfigCliente.moverChunks(1);
			b.setMessage(distancia());
		}).bounds(x + 24, y, w - 48, bh).build();
		this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
			ConfigCliente.moverChunks(-1);
			valor.setMessage(distancia());
		}).bounds(x, y, 20, bh).build());
		this.addRenderableWidget(valor);
		this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
			ConfigCliente.moverChunks(1);
			valor.setMessage(distancia());
		}).bounds(x + w - 20, y, 20, bh).build());
		y += sep;

		this.addRenderableWidget(Button.builder(si("Contorno brillante a lo lejos", ConfigCliente.contorno), b -> {
			ConfigCliente.contorno = !ConfigCliente.contorno;
			b.setMessage(si("Contorno brillante a lo lejos", ConfigCliente.contorno));
		}).bounds(x, y, w, bh).build());
		y += sep;

		this.addRenderableWidget(Button.builder(si("Lista en pantalla (nombre, metros, dirección)", ConfigCliente.hud), b -> {
			ConfigCliente.hud = !ConfigCliente.hud;
			b.setMessage(si("Lista en pantalla (nombre, metros, dirección)", ConfigCliente.hud));
		}).bounds(x, y, w, bh).build());
		y += sep;

		this.addRenderableWidget(Button.builder(si("Marcadores sobre el horizonte", ConfigCliente.marcadores), b -> {
			ConfigCliente.marcadores = !ConfigCliente.marcadores;
			b.setMessage(si("Marcadores sobre el horizonte", ConfigCliente.marcadores));
		}).bounds(x, y, w, bh).build());
		y += sep;

		this.addRenderableWidget(Button.builder(si("Radar (barra localizadora, cualquier server)", ConfigCliente.radar), b -> {
			ConfigCliente.radar = !ConfigCliente.radar;
			b.setMessage(si("Radar (barra localizadora, cualquier server)", ConfigCliente.radar));
		}).bounds(x, y, w, bh).build());
		y += sep;

		this.addRenderableWidget(Button.builder(Component.literal("§aGrupo REE… §7(código y amigos)"), b -> {
			ConfigCliente.guardar();
			this.minecraft.gui.setScreen(new GrupoPantalla(this));
		}).bounds(x, y, w, bh).build());

		this.addRenderableWidget(Button.builder(Component.literal("Guardar y salir"), b -> onClose())
				.bounds(this.width / 2 - 75, y0 + alto - 26, 150, 20).build());
	}

	private static Component si(String texto, boolean v) {
		return Component.literal((v ? "§a✔ " : "§c✘ ") + "§f" + texto);
	}

	private static Component distancia() {
		int c = ConfigCliente.chunks;
		return Component.literal("§fCon REE en el server: §e" + c + " chunks §7(" + (c * 16) + " bloques)");
	}

	/** Estado del servidor en el que estás, en dos renglones. */
	private String[] estado() {
		if (this.minecraft == null || this.minecraft.level == null)
			return new String[] { "§7Entrá a un mundo o servidor para ver el estado.", "" };
		if (REECliente.servidorTieneREE && REECliente.servidorActivo) {
			int efectivo = Math.min(ConfigCliente.chunks, REECliente.servidorMax);
			return new String[] { "§aModo completo ✔ §7el servidor tiene REE (hasta " + REECliente.servidorMax + " chunks)",
					"§7Ves jugadores caminando hasta §f" + efectivo + " chunks" };
		}
		int rd = this.minecraft.options.renderDistance().get();
		return new String[] { "§eModo universal §7(este servidor no tiene REE)",
				"§7Cuerpos hasta tu distancia (" + rd + " chunks) · más lejos: radar" };
	}

	@Override
	public void onClose() {
		ConfigCliente.guardar();
		REECliente.enviarAjustes();
		this.minecraft.gui.setScreen(anterior);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractBackground(g, mouseX, mouseY, delta);
		g.fill(x0 - 3, y0 - 3, x0 + ancho + 3, y0 + alto + 3, 0x88000000);
		g.fill(x0 - 1, y0 - 1, x0 + ancho + 1, y0 + alto + 1, 0xFF4FC3F7);
		g.fillGradient(x0, y0, x0 + ancho, y0 + alto, 0xF0101820, 0xF0080C10);

		Matrix3x2fStack m = g.pose();
		m.pushMatrix();
		m.translate(this.width / 2f, y0 + 7);
		m.scale(1.6f, 1.6f);
		String tit = "REE";
		g.text(this.font, tit, -this.font.width(tit) / 2, 0, 0xFF4FC3F7, true);
		m.popMatrix();
		String sub = "Renderizado Extra de Entidades · " + REE.VERSION;
		g.text(this.font, sub, this.width / 2 - this.font.width(sub) / 2, y0 + 23, 0xFF9E9E9E, false);
		String[] est = estado();
		g.text(this.font, est[0], this.width / 2 - this.font.width(est[0]) / 2, y0 + 33, 0xFFFFFFFF, false);
		g.text(this.font, est[1], this.width / 2 - this.font.width(est[1]) / 2, y0 + 43, 0xFFFFFFFF, false);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
	}
}
