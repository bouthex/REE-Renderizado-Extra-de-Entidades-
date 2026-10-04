# REE — Registro de cambios y errores

## 0.2.0 — modo universal
- Funciona en servidores SIN REE: dibuja a los jugadores que manda el server en todo su rango (sin el corte de ~64 bloques de vanilla).
- Radar con la barra localizadora vanilla: nombre, distancia aproximada (chunk) o dirección para jugadores que el server no manda.
- Marcadores sobre el horizonte en la dirección de cada jugador.
- Opciones nuevas en Mod Menu: Marcadores y Radar. El estado muestra "Modo completo" o "Modo universal".
- Contorno brillante solo en la zona de niebla o sin chunk (antes también en jugadores medio cerca).

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
- [ ] En un server sin REE: aparecen nombres y distancias de jugadores lejanos en la lista y en el horizonte
- [ ] En un server sin REE: un jugador a 100+ bloques (dentro de tu distancia) se ve con su cuerpo
- [ ] Al cambiar la distancia en Mod Menu se aplica al moverse unos bloques
