package com.bout.ree.cliente;

import com.bout.ree.REE;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Grupo REE, todo dentro del mod: no hay que instalar ni prender nada.
 *
 * Cada amigo comparte SU PROPIA posición con los que tienen el mismo código de grupo. Para que los
 * mensajes viajen entre casas se usan "brokers MQTT" públicos y gratuitos (como un buzón compartido en
 * internet). El mod se conecta solo a dos a la vez, por si uno se cae.
 *
 * Privacidad: todo va CIFRADO con AES-256 usando el código del grupo como llave, y el canal tiene un
 * nombre derivado del código. Sin el código, nadie puede leer las posiciones ni saber de quién son.
 */
public final class Grupo {
	public record Amigo(UUID uuid, String nombre, String dim, double x, double y, double z, long ms, long envio) {}

	static final Map<UUID, Amigo> AMIGOS = new ConcurrentHashMap<>();

	/** Brokers MQTT públicos (TCP 1883). Se usan dos a la vez y se toma el dato más nuevo. */
	private static final List<String[]> BROKERS = List.of(
			new String[] { "broker.hivemq.com", "1883" },
			new String[] { "broker.emqx.io", "1883" });

	private static final SecureRandom AZAR = new SecureRandom();
	private static final Map<String, Conexion> CONEXIONES = new ConcurrentHashMap<>();
	private static volatile String codigoActivo = "";
	private static volatile SecretKeySpec llave;
	private static volatile String canal = "";
	/** Mi UUID: los mensajes propios vuelven del broker y hay que ignorarlos (si no, "parpadea"). */
	private static volatile UUID miUuid;
	private static int ticks;

	private Grupo() {}

	// ------------------------------------------------------------------ código de grupo

	/** Letras y números que no se confunden entre sí (sin O/0, I/1/L). */
	private static final String LETRAS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

	/** Genera un código nuevo de 4 caracteres, tipo "K7QM". */
	public static String codigoNuevo() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 4; i++) sb.append(LETRAS.charAt(AZAR.nextInt(LETRAS.length())));
		return sb.toString();
	}

	/** ¿Es un código válido? (4 caracteres de los permitidos) */
	public static boolean codigoValido(String codigo) {
		String c = normalizar(codigo);
		if (c.length() != 4) return false;
		for (char ch : c.toCharArray()) if (LETRAS.indexOf(ch) < 0) return false;
		return true;
	}

	/** ¿Este amigo está mandando su posición ahora mismo? */
	public static boolean enLinea(String nombre) {
		for (Amigo a : AMIGOS.values()) if (a.nombre().equalsIgnoreCase(nombre)) return true;
		return false;
	}

	private static String normalizar(String codigo) {
		return codigo.toUpperCase().replaceAll("[^A-Z0-9]", "");
	}

	// ------------------------------------------------------------------ estado

	public static String estado() {
		if (!ConfigCliente.grupo) return "Apagado";
		if (!codigoValido(ConfigCliente.grupoCodigo)) return "Falta el código del grupo";
		long ok = CONEXIONES.values().stream().filter(c -> c.lista).count();
		if (ok == 0) return "Conectando…";
		return "Conectado (" + ok + "/" + BROKERS.size() + ")";
	}

	public static int conectados() {
		return AMIGOS.size();
	}

	// ------------------------------------------------------------------ tick (hilo del juego)

	static void tick(Minecraft mc) {
		String codigo = normalizar(ConfigCliente.grupoCodigo);
		boolean quiero = ConfigCliente.grupo && codigoValido(codigo) && mc.level != null && mc.player != null;
		if (!quiero) {
			if (!CONEXIONES.isEmpty()) cerrar();
			return;
		}
		if (!codigo.equals(codigoActivo)) {
			cerrar();
			prepararLlave(codigo);
		}
		miUuid = mc.player.getUUID();
		long ahora = System.currentTimeMillis();
		for (String[] b : BROKERS) {
			String k = b[0];
			Conexion c = CONEXIONES.get(k);
			if (c == null || (c.muerta && ahora > c.reintento)) {
				Conexion nueva = new Conexion(b[0], Integer.parseInt(b[1]), canal);
				CONEXIONES.put(k, nueva);
				nueva.start();
			} else if (c.lista && ahora - c.ultimoEnvio > 20000) {
				c.ping();
			}
		}

		// mi posición, 5 veces por segundo (el mod suaviza el movimiento entre medio)
		if (ConfigCliente.compartir && (ticks++ % 4 == 0)) {
			Player yo = mc.player;
			JsonObject o = new JsonObject();
			o.addProperty("u", yo.getUUID().toString());
			o.addProperty("n", yo.getName().getString());
			o.addProperty("d", mc.level.dimension().identifier().toString());
			o.addProperty("x", Math.round(yo.getX() * 100) / 100.0);
			o.addProperty("y", Math.round(yo.getY() * 100) / 100.0);
			o.addProperty("z", Math.round(yo.getZ() * 100) / 100.0);
			o.addProperty("t", ahora);
			byte[] cifrado = cifrar(o.toString().getBytes(StandardCharsets.UTF_8));
			if (cifrado != null) for (Conexion c : CONEXIONES.values()) if (c.lista) c.publicar(cifrado);
		}
		AMIGOS.values().removeIf(a -> ahora - a.ms() > 6000);
	}

	static void cerrar() {
		for (Conexion c : CONEXIONES.values()) c.cerrar();
		CONEXIONES.clear();
		AMIGOS.clear();
		codigoActivo = "";
	}

	// ------------------------------------------------------------------ cifrado

	private static void prepararLlave(String codigo) {
		try {
			SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
			byte[] k = f.generateSecret(new PBEKeySpec(codigo.toCharArray(), "REE-grupo-v2".getBytes(StandardCharsets.UTF_8), 200000, 256)).getEncoded();
			llave = new SecretKeySpec(k, "AES");
			byte[] h = MessageDigest.getInstance("SHA-256").digest(("REE-canal-v2:" + codigo).getBytes(StandardCharsets.UTF_8));
			canal = "ree/v2/" + HexFormat.of().formatHex(h, 0, 16);
			codigoActivo = codigo;
		} catch (Exception e) {
			REE.LOG.warn("REE Grupo: no se pudo preparar el cifrado", e);
		}
	}

	private static byte[] cifrar(byte[] datos) {
		try {
			byte[] iv = new byte[12];
			AZAR.nextBytes(iv);
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.ENCRYPT_MODE, llave, new GCMParameterSpec(128, iv));
			byte[] ct = c.doFinal(datos);
			return ByteBuffer.allocate(12 + ct.length).put(iv).put(ct).array();
		} catch (Exception e) {
			return null;
		}
	}

	private static void recibir(byte[] datos) {
		try {
			if (datos.length < 29) return;
			Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
			c.init(Cipher.DECRYPT_MODE, llave, new GCMParameterSpec(128, datos, 0, 12));
			String texto = new String(c.doFinal(datos, 12, datos.length - 12), StandardCharsets.UTF_8);
			JsonObject o = JsonParser.parseString(texto).getAsJsonObject();
			long t = o.get("t").getAsLong();
			if (Math.abs(System.currentTimeMillis() - t) > 30000) return; // mensaje viejo o reloj muy corrido
			UUID id = UUID.fromString(o.get("u").getAsString());
			if (id.equals(miUuid)) return; // soy yo
			// llega por dos brokers: si este dato es más viejo que el que ya tenemos, se descarta
			Amigo anterior = AMIGOS.get(id);
			if (anterior != null && t <= anterior.envio()) return;
			AMIGOS.put(id, new Amigo(id, o.get("n").getAsString(), o.get("d").getAsString(),
					o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(),
					System.currentTimeMillis(), t));
		} catch (Exception ignorado) {
			// no es de nuestro grupo, o está roto: se ignora
		}
	}

	// ------------------------------------------------------------------ cliente MQTT mínimo (3.1.1, QoS 0)

	private static final class Conexion extends Thread {
		private final String host;
		private final int puerto;
		private final String tema;
		private Socket socket;
		private OutputStream salida;
		volatile boolean lista, muerta, cerrada;
		volatile long reintento, ultimoEnvio;

		Conexion(String host, int puerto, String tema) {
			super("REE-Grupo-" + host);
			setDaemon(true);
			this.host = host;
			this.puerto = puerto;
			this.tema = tema;
		}

		@Override
		public void run() {
			try {
				socket = new Socket();
				socket.connect(new InetSocketAddress(host, puerto), 8000);
				socket.setSoTimeout(60000);
				socket.setTcpNoDelay(true);
				salida = socket.getOutputStream();
				DataInputStream entrada = new DataInputStream(socket.getInputStream());

				// CONNECT
				ByteArrayOutputStream v = new ByteArrayOutputStream();
				texto(v, "MQTT");
				v.write(4);    // versión 3.1.1
				v.write(0x02); // sesión limpia
				v.write(0); v.write(30); // keep alive 30 s
				texto(v, "ree-" + Long.toHexString(AZAR.nextLong()));
				enviar(0x10, v.toByteArray());

				// SUBSCRIBE al canal del grupo
				ByteArrayOutputStream s = new ByteArrayOutputStream();
				s.write(0); s.write(1); // id de paquete
				texto(s, tema);
				s.write(0); // QoS 0
				enviar(0x82, s.toByteArray());

				while (!cerrada) {
					int cabecera = entrada.readUnsignedByte();
					int largo = 0, mult = 1, b;
					do {
						b = entrada.readUnsignedByte();
						largo += (b & 0x7F) * mult;
						mult *= 128;
					} while ((b & 0x80) != 0 && mult <= 128 * 128 * 128);
					if (largo > 8192) throw new IOException("paquete demasiado grande");
					byte[] cuerpo = new byte[largo];
					entrada.readFully(cuerpo);
					int tipo = cabecera >> 4;
					if (tipo == 2) { // CONNACK
						if (cuerpo.length < 2 || cuerpo[1] != 0) throw new IOException("broker rechazó la conexión");
					} else if (tipo == 9) { // SUBACK
						lista = true;
					} else if (tipo == 3) { // PUBLISH
						int lt = ((cuerpo[0] & 0xFF) << 8) | (cuerpo[1] & 0xFF);
						int inicio = 2 + lt + (((cabecera >> 1) & 3) > 0 ? 2 : 0);
						if (inicio <= cuerpo.length) {
							byte[] carga = new byte[cuerpo.length - inicio];
							System.arraycopy(cuerpo, inicio, carga, 0, carga.length);
							recibir(carga);
						}
					}
				}
			} catch (Exception e) {
				if (!cerrada) REE.LOG.debug("REE Grupo: se cortó {} ({})", host, e.toString());
			} finally {
				lista = false;
				muerta = true;
				reintento = System.currentTimeMillis() + 10000;
				try { if (socket != null) socket.close(); } catch (IOException ignorado) {}
			}
		}

		void publicar(byte[] carga) {
			try {
				ByteArrayOutputStream p = new ByteArrayOutputStream();
				texto(p, tema);
				p.write(carga);
				enviar(0x30, p.toByteArray());
			} catch (IOException e) {
				cerrar();
			}
		}

		void ping() {
			try { enviar(0xC0, new byte[0]); } catch (IOException e) { cerrar(); }
		}

		void cerrar() {
			cerrada = true;
			lista = false;
			try {
				if (salida != null) enviar(0xE0, new byte[0]); // DISCONNECT
			} catch (IOException ignorado) {}
			try { if (socket != null) socket.close(); } catch (IOException ignorado) {}
		}

		private synchronized void enviar(int cabecera, byte[] cuerpo) throws IOException {
			ByteArrayOutputStream f = new ByteArrayOutputStream();
			f.write(cabecera);
			int largo = cuerpo.length;
			do {
				int b = largo % 128;
				largo /= 128;
				if (largo > 0) b |= 0x80;
				f.write(b);
			} while (largo > 0);
			f.write(cuerpo);
			salida.write(f.toByteArray());
			salida.flush();
			ultimoEnvio = System.currentTimeMillis();
		}

		private static void texto(ByteArrayOutputStream o, String s) throws IOException {
			byte[] b = s.getBytes(StandardCharsets.UTF_8);
			o.write(b.length >> 8);
			o.write(b.length & 0xFF);
			o.write(b);
		}
	}
}
