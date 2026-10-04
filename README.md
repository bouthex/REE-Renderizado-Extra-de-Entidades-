# REE — Renderizado Extra de Entidades

Mod Fabric para **Minecraft 26.2** que te deja ver a los otros jugadores mucho más allá de tu distancia
de renderizado, **moviéndose en tiempo real**, sin cargar ni un chunk extra. Funciona en servidores,
en mundos abiertos en LAN y en cualquier partida con más gente.

## Instalación

| Dónde | ¿Hace falta REE? |
|---|---|
| Servidor dedicado | **Sí** (el server es el que decide qué te manda) |
| Mundo abierto en LAN | Ya está: el host tiene el mod, así que su servidor integrado lo trae |
| Cada jugador que quiera ver lejos | **Sí** + Fabric API (Mod Menu opcional, para la pantalla de config) |
| Jugadores sin REE | Pueden entrar igual: para ellos todo es vanilla |

## Configuración

**Cliente** (Mod Menu → REE, o `config/ree.properties`): activar/desactivar, distancia (12 a 256 chunks),
contorno brillante a lo lejos y lista en pantalla con nombre, metros y flecha de dirección.

**Servidor** (`config/ree-servidor.properties`): `activo=si/no` y `chunks_maximos` (tope que puede pedir cada jugador, por defecto 64).

## Por qué otros mods no lo logran

Un mod solo-cliente no puede mostrar a alguien que el servidor nunca le mandó: vanilla deja de enviar a un
jugador cuando sale de tu distancia de vista (`ChunkMap.TrackedEntity#updatePlayer`). Los mods de LOD
(Distant Horizons) dibujan terreno pero no entidades, y los de caché de chunks (Bobby) guardan bloques, no
jugadores. REE ataca las dos puntas:

1. **Servidor:** amplía el seguimiento SOLO de entidades jugador y SOLO para quien tiene REE, sin mandar chunks.
   Llegan los paquetes vanilla de siempre (posición, giro, pose, armadura, ítem en mano).
2. **Cliente:** deja dibujar a esos jugadores aunque su chunk no exista en tu cliente (sin corte por sección,
   por distancia ni por el culling de Sodium) y les pone contorno para que la niebla no los tape.

Los jugadores en Minecraft siempre "tickean", así que su animación y movimiento se ven fluidos aunque estén
en un chunk vacío para tu cliente.

## Compilar

`compilar.bat` en Windows, o subilo a GitHub: el workflow deja el `.jar` en *Actions → build → ree-jar*.
