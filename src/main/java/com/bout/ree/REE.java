package com.bout.ree;

import com.bout.ree.red.AjustesPayload;
import com.bout.ree.red.ServidorPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REE - Renderizado Extra de Entidades.
 *
 * Parte común / servidor. Corre en el servidor dedicado y también en el servidor integrado
 * (singleplayer y "Abrir en LAN"), así que el host de un LAN no necesita nada más.
 *
 * Cómo funciona:
 *  1. El cliente con REE le avisa al servidor a cuántos chunks quiere ver jugadores (AjustesPayload).
 *  2. El servidor guarda ese número (limitado por su propio máximo) y, en TrackedEntityMixin,
 *     amplía el rango de seguimiento SOLO de entidades jugador y SOLO para esos clientes.
 *  3. El cliente recibe los paquetes vanilla de siempre (aparecer, moverse, girar, animaciones,
 *     armadura...) aunque el chunk de ese jugador no esté cargado. No se manda ni un chunk extra.
 *  4. Del lado del cliente, los mixins de render dejan dibujar a esos jugadores aunque estén
 *     fuera de los chunks cargados (ver paquete cliente.mixin).
 *
 * Los clientes sin REE no reciben nada distinto: para ellos el servidor se comporta como vanilla.
 */
public class REE implements ModInitializer {
	public static final String ID = "ree";
	public static final String VERSION = "0.5.0";
	public static final Logger LOG = LoggerFactory.getLogger("REE");

	@Override
	public void onInitialize() {
		// Los tipos de paquete se registran en los dos lados (cliente y servidor)
		PayloadTypeRegistry.serverboundPlay().register(AjustesPayload.TYPE, AjustesPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ServidorPayload.TYPE, ServidorPayload.CODEC);

		REEServidor.cargarConfig();

		// El cliente nos dice a cuántos chunks quiere ver jugadores
		ServerPlayNetworking.registerGlobalReceiver(AjustesPayload.TYPE, (payload, context) ->
				REEServidor.recibirAjustes(context.player(), payload));

		// Al entrar, si el cliente tiene REE, le contamos qué permite este servidor
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (ServerPlayNetworking.canSend(handler.getPlayer(), ServidorPayload.TYPE)) {
				sender.sendPacket(new ServidorPayload(REEServidor.activo, REEServidor.chunksMaximos));
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> REEServidor.olvidar(handler.getPlayer()));

		LOG.info("REE {} listo (servidor: {}, máximo {} chunks)", VERSION, REEServidor.activo ? "activo" : "apagado", REEServidor.chunksMaximos);
	}
}
