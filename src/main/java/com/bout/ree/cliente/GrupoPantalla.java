package com.bout.ree.cliente;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2fStack;

/**
 * Grupo REE: un código de 4 caracteres + tu lista de amigos.
 * Uno toca "Nuevo", dice el código por el chat ("unite a K7QM") y los demás lo escriben en "Unirse".
 */
public class GrupoPantalla extends Screen {
	private static final int VERDE = 0xFF7CE38B;
	private static final int MAX_VISIBLES = 8;
	private final Screen anterior;
	private int x0, y0, ancho, alto, yCodigo, yLista;
	private EditBox unirse, nombre;
	private String aviso = "";

	public GrupoPantalla(Screen anterior) {
		super(Component.literal("REE - Grupo"));
		this.anterior = anterior;
	}

	@Override
	protected void init() {
		ancho = Math.min(300, this.width - 20);
		alto = 250;
		x0 = (this.width - ancho) / 2;
		y0 = Math.max(4, (this.height - alto) / 2);
		int x = x0 + 12, w = ancho - 24, y = y0 + 32;

		this.addRenderableWidget(Button.builder(si("Activado", ConfigCliente.grupo), b -> {
			ConfigCliente.grupo = !ConfigCliente.grupo;
			b.setMessage(si("Activado", ConfigCliente.grupo));
		}).bounds(x, y, w / 2 - 2, 20).build());
		this.addRenderableWidget(Button.builder(si("Compartir mi posición", ConfigCliente.compartir), b -> {
			ConfigCliente.compartir = !ConfigCliente.compartir;
			b.setMessage(si("Compartir mi posición", ConfigCliente.compartir));
		}).bounds(x + w / 2 + 2, y, w / 2 - 2, 20).build());

		// El código, grande, con botón para generar uno nuevo
		yCodigo = y + 38;
		this.addRenderableWidget(Button.builder(Component.literal("Nuevo"), b -> {
			ConfigCliente.grupoCodigo = Grupo.codigoNuevo();
			ConfigCliente.grupo = true;
			reabrir("§aCódigo nuevo: decíselo a tus amigos por el chat");
		}).bounds(x + w - 50, yCodigo + 2, 50, 20).build());

		// Unirse al código de otro
		y = yCodigo + 36;
		unirse = new EditBox(this.font, x, y, 60, 18, Component.empty());
		unirse.setMaxLength(4);
		unirse.setHint(Component.literal("§8K7QM"));
		this.addRenderableWidget(unirse);
		this.addRenderableWidget(Button.builder(Component.literal("Unirse"), b -> {
			String c = unirse.getValue().trim().toUpperCase();
			if (Grupo.codigoValido(c)) {
				ConfigCliente.grupoCodigo = c;
				ConfigCliente.grupo = true;
				reabrir("§aTe uniste al grupo " + c);
			} else {
				aviso = "§cEl código son 4 letras/números";
			}
		}).bounds(x + 64, y - 1, 56, 20).build());

		// Agregar amigo
		nombre = new EditBox(this.font, x + w - 124, y, 80, 18, Component.empty());
		nombre.setMaxLength(16);
		nombre.setHint(Component.literal("§8nombre"));
		this.addRenderableWidget(nombre);
		this.addRenderableWidget(Button.builder(Component.literal("+ Amigo"), b -> {
			String n = nombre.getValue().trim();
			if (n.matches("[A-Za-z0-9_]{2,16}") && !ConfigCliente.esAmigoExacto(n)) {
				ConfigCliente.AMIGOS.add(n);
				reabrir("§aAgregaste a " + n);
			} else {
				aviso = "§cNombre inválido o ya está en la lista";
			}
		}).bounds(x + w - 42, y - 1, 42, 20).build());

		// Lista de amigos: dos columnas, punto verde si está en línea, botón para sacar
		yLista = y + 36;
		int col = w / 2 - 3;
		for (int i = 0; i < Math.min(MAX_VISIBLES, ConfigCliente.AMIGOS.size()); i++) {
			String a = ConfigCliente.AMIGOS.get(i);
			int cx = x + (i % 2) * (col + 6), cy = yLista + (i / 2) * 18;
			this.addRenderableWidget(Button.builder(Component.literal("§7×"), b -> {
				ConfigCliente.AMIGOS.remove(a);
				reabrir("§7Sacaste a " + a);
			}).bounds(cx + col - 15, cy + 1, 14, 14).build());
		}

		this.addRenderableWidget(Button.builder(Component.literal("Listo"), b -> onClose())
				.bounds(this.width / 2 - 50, y0 + alto - 26, 100, 20).build());
	}

	private void reabrir(String mensaje) {
		ConfigCliente.guardar();
		GrupoPantalla nueva = new GrupoPantalla(anterior);
		nueva.aviso = mensaje;
		this.minecraft.gui.setScreen(nueva);
	}

	private static Component si(String texto, boolean v) {
		return Component.literal((v ? "§a● " : "§8● ") + "§f" + texto);
	}

	@Override
	public void onClose() {
		ConfigCliente.guardar();
		this.minecraft.gui.setScreen(anterior);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractBackground(g, mouseX, mouseY, delta);
		int x = x0 + 12, w = ancho - 24;

		// panel
		g.fill(x0 - 3, y0 - 3, x0 + ancho + 3, y0 + alto + 3, 0x66000000);
		g.fillGradient(x0 - 1, y0 - 1, x0 + ancho + 1, y0 + alto + 1, VERDE, 0xFF2E7D4F);
		g.fillGradient(x0, y0, x0 + ancho, y0 + alto, 0xF2121C17, 0xF2080D0A);
		g.fill(x0, y0, x0 + ancho, y0 + 26, 0x2238FF8A);

		// título + estado
		String tit = "Grupo REE";
		g.text(this.font, tit, x, y0 + 6, VERDE, true);
		String est = Grupo.estado();
		boolean ok = est.startsWith("Conectado");
		int colorEstado = ok ? VERDE : ConfigCliente.grupo ? 0xFFFFE082 : 0xFF8A8A8A;
		String est2 = est + (Grupo.conectados() > 0 ? " · " + Grupo.conectados() + " en línea" : "");
		int ancEst = Math.round(this.font.width(est2) * 0.75f);
		g.fill(x + w - ancEst - 8, y0 + 6, x + w - ancEst - 4, y0 + 10, colorEstado);
		escalado(g, est2, x + w - ancEst, y0 + 5, 0.75f, 0xFFB0B0B0);

		// código grande en cajitas
		escalado(g, "TU CÓDIGO", x, yCodigo - 10, 0.7f, 0xFF8A8A8A);
		String cod = Grupo.codigoValido(ConfigCliente.grupoCodigo) ? ConfigCliente.grupoCodigo.toUpperCase() : "----";
		for (int i = 0; i < 4; i++) {
			int bx = x + i * 26;
			g.fill(bx, yCodigo, bx + 22, yCodigo + 24, 0xFF0B110D);
			g.fill(bx, yCodigo + 22, bx + 22, yCodigo + 24, VERDE);
			Matrix3x2fStack m = g.pose();
			m.pushMatrix();
			m.translate(bx + 11, yCodigo + 5);
			m.scale(2f, 2f);
			String c = String.valueOf(cod.charAt(i));
			g.text(this.font, c, -this.font.width(c) / 2, 0, 0xFFFFFFFF, true);
			m.popMatrix();
		}
		escalado(g, "Decíselo a tus amigos por el chat", x + 110, yCodigo + 4, 0.65f, 0xFF8A8A8A);
		escalado(g, "y que lo pongan en \"Unirse\"", x + 110, yCodigo + 12, 0.65f, 0xFF8A8A8A);

		// etiquetas de la fila de abajo
		int yFila = yCodigo + 36;
		escalado(g, "UNIRSE A OTRO CÓDIGO", x, yFila - 9, 0.7f, 0xFF8A8A8A);
		escalado(g, "AGREGAR AMIGO", x + w - 124, yFila - 9, 0.7f, 0xFF8A8A8A);

		// separador + amigos
		g.fill(x, yLista - 12, x + w, yLista - 11, 0x33FFFFFF);
		int n = ConfigCliente.AMIGOS.size();
		escalado(g, "AMIGOS" + (n == 0 ? "  §8(vacío = ves a todos los del código)" : "  §8" + n), x, yLista - 8, 0.7f, 0xFF8A8A8A);
		int col = w / 2 - 3;
		for (int i = 0; i < Math.min(MAX_VISIBLES, n); i++) {
			String a = ConfigCliente.AMIGOS.get(i);
			int cx = x + (i % 2) * (col + 6), cy = yLista + (i / 2) * 18;
			boolean linea = Grupo.enLinea(a);
			g.fill(cx, cy, cx + col, cy + 16, 0x22FFFFFF);
			g.fill(cx + 5, cy + 6, cx + 9, cy + 10, linea ? VERDE : 0xFF555555);
			g.text(this.font, a, cx + 14, cy + 4, linea ? 0xFFFFFFFF : 0xFFAAAAAA, false);
		}
		if (n > MAX_VISIBLES) escalado(g, "+" + (n - MAX_VISIBLES) + " más", x, yLista + 4 * 18 + 2, 0.7f, 0xFF8A8A8A);

		// aviso de la última acción
		if (!aviso.isEmpty()) {
			escalado(g, aviso, this.width / 2f - this.font.width(aviso) * 0.75f / 2, y0 + alto - 38, 0.75f, 0xFFFFFFFF);
		}
	}

	private void escalado(GuiGraphicsExtractor g, String t, float x, float y, float e, int color) {
		Matrix3x2fStack m = g.pose();
		m.pushMatrix();
		m.translate(x, y);
		m.scale(e, e);
		g.text(this.font, t, 0, 0, color, false);
		m.popMatrix();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
	}
}
