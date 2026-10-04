package com.bout.ree.red;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Servidor -> cliente: "este servidor tiene REE y permite hasta X chunks". */
public record ServidorPayload(boolean activo, int chunksMaximos) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<ServidorPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("ree", "servidor"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ServidorPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, ServidorPayload::activo,
			ByteBufCodecs.VAR_INT, ServidorPayload::chunksMaximos,
			ServidorPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
