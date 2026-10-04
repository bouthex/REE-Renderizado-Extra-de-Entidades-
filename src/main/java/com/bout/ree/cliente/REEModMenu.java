package com.bout.ree.cliente;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Botón de configuración en Mod Menu. Si Mod Menu no está instalado, esta clase nunca se carga. */
public class REEModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return REEPantalla::new;
	}
}
