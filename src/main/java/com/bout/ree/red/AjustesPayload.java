package com.bout.ree.red;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Cliente -> servidor: "tengo REE, quiero ver jugadores hasta X chunks". */
public record AjustesPayload(boolean activo, int chunks) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<AjustesPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("ree", "ajustes"));
	public static final StreamCodec<RegistryFriendlyByteBuf, AjustesPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, AjustesPayload::activo,
			ByteBufCodecs.VAR_INT, AjustesPayload::chunks,
			AjustesPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
