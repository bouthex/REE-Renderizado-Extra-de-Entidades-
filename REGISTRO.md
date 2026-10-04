# REE — Registro de cambios y errores

## 0.1.0 — primera versión
- Servidor: rango extra de seguimiento para jugadores (mixin en `ChunkMap$TrackedEntity#updatePlayer`), solo para clientes con REE.
- Cliente: render de jugadores fuera de chunks cargados, sin corte por distancia, contorno brillante y lista en pantalla.
- Config cliente (Mod Menu) y servidor (`ree-servidor.properties`).

## Errores
| Código | Qué pasó | Estado |
|---|---|---|
| — | — | — |

## A probar en juego
- [ ] Jugador a 600+ bloques se ve caminar y saltar
- [ ] El contorno se ve a través de la niebla
- [ ] Con Sodium instalado también aparecen
- [ ] Un jugador sin REE entra al server sin problemas
- [ ] Al cambiar la distancia en Mod Menu se aplica al moverse unos bloques
