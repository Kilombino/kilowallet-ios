# Kilowallet iOS → paridad con Android 1.4.4

Análisis del 1 nov 2026. Comparación de **Kilowallet Android 1.4.4** (`~/Projects/blake2b/kilowallet`, f5bef51, versionCode 225) con **Kilowallet iOS 0.4.1** (este repo, 5ab9f2e). Documento interno: no hay que publicarlo.

**Planteamiento acordado (Kilombino, 1 nov):** Russo recibe **una sola versión** con todo, es decir paridad de pantallas, on-chain, Ark y Lightning. Por dentro el trabajo va por **hitos** (ramas o etapas que se comprueban en el CI de macOS). Ninguno se publica por separado: los hitos desembocan en una única release `v1.0.0`, propuesta como **1.0.0 = Android 1.4.4**.

Referencia visual: las capturas de Android de la paridad web (`/mnt/datos2/kw-web-parity/raw-android/*.png`, Android 1.4.x), que es lo que hizo kilowallet-server 0.10.0-beta1 pantalla por pantalla.

---

## 1. Pantallas y navegación

| Android 1.4.4 | iOS 0.4.1 | Falta |
|---|---|---|
| **Cabecera:** píldora de cartera 🔥 caliente / 👁 vigilar / 🫙 (Jam), "sencillo" (modo simple), ⚙ ajustes, ⏻ salir/bloquear, moneda (abre Spamcoin) | Cabecera simple con "settings" | Píldora de carteras (cambiar entre caliente y vigilar), modo simple, salir/bloquear, acceso a la otra cadena |
| **Pestañas:** BTC · ⚡ LIGHTNING · 🌀 COINJOIN · ⛵ ARK | Sin pestañas: inicio y Pools como pantalla aparte | Barra de 4 pestañas (Kilojoin en lugar de COINJOIN por las normas de App Store) |
| **BTC:** tarjeta de estado del escaneo (servidor, huella fijada), SALDO con hide, cuenta atrás de refresco, sats/₿, fiat con chips USD/EUR/▾, direcciones usadas y altura, tipo de dirección, nota "CÓMO SE ENCUENTRAN TUS MONEDAS", RECIBIR / ENVIAR, movimientos | Saldo, fiat, recibir/enviar, movimientos con RBF | Tarjeta de escaneo, chips fiat en el inicio, textos de ayuda, **HISTORY** completo (fecha, cartera, dirección), maduración visible |
| **Recibir:** QR, ◀ ▶, importe BIP-21, **bc1q/bc1p** | Igual, solo bc1q | Taproot, Silent Payments (sp1) si Android lo muestra al recibir |
| **Enviar:** control de monedas, RBF, CPFP, revisión, PSBT/UR para firmar fuera (SeedSigner), contactos, Silent Payments | Enviar básico, RBF acelerar/cancelar | Control de monedas a nivel Android, **CPFP**, PSBT animado (UR) y escaneo, contactos, envío a sp1 |
| **Onboarding:** crear (12/24, **dados**, frase de contraseña), comprobar palabras, restaurar, vigilar (zpub/xpub, **ambas cadenas**, Jam), pregunta de coinjoin | Crear, comprobar, restaurar, vigilar zpub | **Dados** (DiceCheck), vigilar Jam y ambas cadenas, pregunta de Kilojoin |
| **Ajustes:** idioma, Spamcoin ABRIR, aviso al cambiar el saldo, refresco en primer y segundo plano, Ark, Coinjoin y su aviso de pools, "que la ronda vaya sola", nodo propio y explorador, crash report, copia completa **.kab**, cambiar o crear cartera, olvidar, acerca de, comprobar actualización | Idioma, servidor y huella, zpub, olvidar, enlaces | La mayoría (ver §2). En iOS no aplican el actualizador ni el "permitir segundo plano" |
| **Coinjoin:** pools, en curso, historial, abrir pool, pagar a 1–5, moneda perfecta, rondas perpetuas | Pools (unirse, abrir, pagar 1–5) | Historial, moneda perfecta, rondas automáticas, asientos múltiples, Taproot |
| **Ark:** antes, activar, cartera, recibir, enviar, entrar ("board"), renovar, emergencia, palabras | — | Todo |
| **Lightning:** activar, canales, abrir y cerrar, splice, pagar, recibir, ofertas BOLT12, entrada de liquidez (kilo-lsp), puente SHA-256, mover el nodo, rescate | — | Todo |
| **Otros:** widget, conversor, modo calle, avisos de ronda, JoinMarket (vigilar y gastar bond) | — | Widget (WidgetKit) y el resto, ver §2 |

Capturas de iOS: el CI (`.github/workflows/ios.yml`) genera las pantallas demo `welcome, create, restore, watch, home, receive, settings, replace, send, pool-intro, pool-create, pool-join, pool-pay, review` (+ `@es`, `@zh`). Para la paridad se añaden demos por pantalla nueva y se comparan con `kw-web-parity/raw-android`.

---

## 2. Funciones on-chain que faltan

Portabilidad: **P** = Kotlin puro, se copia al core KMP con sus vectores. **A** = depende de Android (Context, prefs, servicios o notificaciones) y la lógica es portable si se separa. **N** = hace falta código nativo de iOS. Esfuerzo: S ≤ 1 día, M 2–4 días, L ≥ 1 semana.

| Función | Android (archivos) | Port. | Esf. | Nota |
|---|---|---|---|---|
| Taproot bc1p, caliente y vigilar | `crypto/Schnorr.kt`, `crypto/TxBuilder.kt`, `ui/HotWallet.kt`, `crypto/Address.kt` | P | M | Hacen falta vectores de firma Schnorr contra Android, como ya pasa con las unified. secp256k1-kmp tiene Schnorr |
| CPFP | `ui/WalletViewModel.kt`, `ln/TxSize.kt` | P/A | S | Tamaño del hijo y tasa ya están en `TxSize.kt` |
| HISTORY (fecha, cartera, dirección) | `data/Scanner.kt`, `ui/WalletChrome.kt`, `ui/MainActivity.kt` | P | M | Scanner de iOS ya es un port; falta la fecha por cabecera y la UI |
| Maduración de coinbase (Knots 29.4.3) | `data/Scanner.kt:544` `maturityFor` (LONG_START / LONG_MATURITY, 973 440 → HISTORICAL, resto 100) | P | S | **iOS sigue con la regla fija 973 440–979 919 de 6 480**: hay que cambiarla ya, porque desde 979 920 rige la regla larga. Además falta mostrar las monedas inmaduras con fecha estimada |
| Semillas con dados | `crypto/DiceCheck.kt`, `crypto/Bip39.kt`, `ui/HotWallet.kt` | P | S | |
| Frase de contraseña BIP-39 | ya está en iOS | — | — | Comprobar que la UI coincide |
| Copia completa `.kab` cifrada | `data/AppBackup.kt`, `data/SecretBox.kt`, `crypto/XChaCha20Poly1305.kt`, `crypto/Scrypt.kt`, `ui/FullBackup.kt` | P+A | M | Mismo formato de archivo, para restaurar entre Android e iOS. En iOS se guarda con el selector de documentos |
| Moneda perfecta | `coinjoin/PerfectCoin.kt` | A | M | Lógica portable; el disparo en segundo plano no (ver §3.4) |
| Rondas automáticas / perpetuas | `coinjoin/Perpetual.kt`, `coinjoin/AutoKeyVault.kt` | A | L | En Android se apoya en un servicio en primer plano. En iOS solo con la app abierta o con BGProcessing; el vault de claves va al Keychain |
| Un aviso por ronda | `coinjoin/RoundNotifications.kt`, `coinjoin/CoinjoinAlerts.kt` | A | S | UNUserNotificationCenter con notificaciones locales |
| Asientos múltiples (ownSeats) | `coinjoin/PoolSession.kt`, `coinjoin/CoinjoinHub.kt` | P | M | El PoolSession de iOS es un port de 1.3.9: hay que traer los cambios 1.3.9 → 1.4.4 con interop |
| Pools Taproot | `coinjoin/CoinjoinTx.kt`, `coinjoin/Protocol.kt` | P | M | Depende de Taproot |
| Historial de coinjoin | `ui/CoinjoinScreen.kt`, `coinjoin/CoinjoinHub.kt` | A | S | |
| Contactos | `data/Contacts.kt`, `ui/ContactsUi.kt` | A | S | |
| Silent Payments (sp1) | `crypto/SilentPayment.kt`, `crypto/Bech32.kt` | P | M | |
| PSBT y UR animado (SeedSigner) | `crypto/Psbt.kt`, `crypto/Ur.kt`, `ui/PsbtQr.kt` | P+N | M | El escáner de QR ya existe en Swift |
| Vista Spamcoin / Corecoin (ambas cadenas) | `chain/Chain.kt`, `ui/MainActivity.kt` | P | M | En App Store se llama "Corecoin" |
| Nodo propio por RPC (y Tor) | `chain/NodeRpc.kt`, `ui/NodeRpcUi.kt`, `chain/Tor.kt` | P+N | M | Sin Tor: solo LAN o clearnet. Arti se puede compilar para iOS (Rust) más adelante |
| Avisos de cambio de saldo | `data/WatchService.kt`, `data/Notifier.kt` | A+N | M | iOS: BGAppRefreshTask, oportunista y sin intervalo fijo (ver §3.4) |
| Widget | `widget/XbtWidget.kt`, `widget/WidgetFormat.kt` | N | M | WidgetKit en SwiftUI, extensión aparte |
| Modo simple / modo calle | `ui/SimpleMode.kt`, `ui/StreetMode.kt` | A | M | |
| Conversor | `ui/ConverterActivity.kt` | A | S | |
| JoinMarket (vigilar Jam, gastar bond) | `data/JoinMarket.kt`, `data/JamWatch.kt`, `crypto/BondPsbt.kt`, `ui/JamScreen.kt`, `ui/BondSpendScreen.kt` | P+A | M | |
| Rescate de LND anterior a la bifurcación | `data/LndRescue.kt`, `ui/LndRescue.kt` | P | S | |
| Crash report | `data/CrashLog.kt`, `data/CrashReport.kt` | N | S | |
| Actualizador | `data/AppUpdater.kt`, `data/UpdateCheck.kt` | — | — | **No aplica** en App Store |

---

## 3. Ark y Lightning

### 3.1 Ark (hito 3)

- **Android:** motor `libkilombino_ark.so`, de la crate Rust `kilombino-ark` de `Kilombino/paperclip-wallet-app` rama `kilombino-esplora` (ver `ark-engine/ENGINE`, código en `/mnt/datos2/ark-desk/kilombino-ark`).
  - Arranca un demonio bark dentro del proceso con API REST en 127.0.0.1 y token aleatorio. Lo pinta `ark/Ark.kt` (1 077 líneas), `ui/ArkScreen.kt`, `ui/ArkWords.kt`, `ui/ArkEmergency.kt`.
  - Las palabras se guardan en el Keystore (`ark/ArkSeed.kt`, `ark/ArkDerive.kt`) y la copia en `ark/ArkBackup.kt`.
  - La interfaz con Kotlin es JNI (`src/lib.rs`: `Java_…_start/stop` y unas primitivas secp).
- **iOS:**
  - Añadir a `lib.rs` 6–7 funciones `extern "C"` equivalentes a las JNI, unas 80 líneas.
  - Compilar `staticlib` para `aarch64-apple-ios` y `aarch64-apple-ios-sim` y empaquetarlo con `xcodebuild -create-xcframework`. Pesa unos 20–25 MB por arquitectura, como en Android (21 MB).
  - `Ark.kt` habla con el motor por REST, así que pasa al KMP con `Bridge.swift httpGet`, que ya existe. Las palabras van al Keychain.
- **Riesgos:**
  - Las monedas Ark caducan (unos 30 días) si nadie las renueva. En iOS solo se renuevan con la app abierta o con BGProcessing, así que hay que avisar con notificación local y mostrarlo claro (renovación a mano).
  - Las salidas de emergencia (ArkEmergency) deben funcionar sin el servidor.

### 3.2 Lightning (hito 4, el núcleo de lo que pide Russo)

**Cómo es en Android:**
- **Motor:** LND del fork de Paul `paulscode/lightning-fork` `v0.21.4-beta-blake2b.21.1` (commit 7c8f252b1), con neutrino `Kilombino/neutrino-blake2b v0.17.1-blake2b.1.1`, que anuncia **NODE_BLAKE2B** (Knots 29.4.3 solo sirve cabeceras posteriores a la bifurcación a esos peers).
  - Se compila con `gomobile bind` en el clon `/mnt/datos2/lnd-mobile21` mediante `lnd-engine/build.sh`, con Go 1.27.3.
  - Tags: `mobile signrpc walletrpc chainrpc invoicesrpc routerrpc peersrpc neutrinorpc watchtowerrpc wtclientrpc offersrpc`.
  - Resultado: `app/libs/Lndmobile.aar` de **49 MB**, con arm64 y amd64.
- **Integración en Kotlin, unas 1 800 líneas:**
  - `ln/Lnd.kt` (769): arranque con `Lndmobile.start(--lnddir … --configfile …)` y llamadas unarias y en stream con protobuf escrito a mano en `ln/Proto.kt` (sin código generado). Cubre wallet, canales, splice, pagos, facturas, BOLT12 (`decodeOffer`/`payOffer`/`reusableOffer`), watchtower, SCB y signMessage.
  - `ln/Lightning.kt` (451): estado, encendido, crear o desbloquear la wallet, torre y peers. Es la única parte con mucho Android (51 referencias a Context/prefs).
  - `ln/LnSeed.kt`: la clave de LND sale de las mismas palabras, con raíz propia.
  - `ln/Inbound.kt` (kilo-lsp), `ln/Bridge.kt` (puente SHA-256), `ln/LnMove.kt` (mover el nodo cifrado), `data/Scb.kt` (listar canales del SCB), `data/LndRescue.kt`.
  - UI: `ui/LightningScreen.kt` (1 931 líneas), `ui/LndRescue.kt`.
- **Qué se porta tal cual al KMP:** `Lnd.kt` (salvo el `import lndmobile.*`, que pasa a ser una interfaz `LndEngine` implementada en Swift), `Proto.kt`, `TxSize.kt`, `LnSeed.kt` (sus vectores ya están en `tools/android-vectors`), `Inbound.kt`, `Scb.kt`, `LndRescue.kt` y la lógica de `Bridge.kt` y `LnMove.kt`. `Lightning.kt` y la UI se reescriben en Compose MP siguiendo el original, como Pools.kt en 0.4.0.

**Compilar el xcframework:**
- La Makefile del fork ya tiene el objetivo `ios`: `gomobile bind -target=ios,iossimulator -tags="mobile $(RPC_TAGS)" … -o Lndmobile.xcframework ./mobile`, tras `mobile-rpc`.
- Pasos:
  1. Fijar el commit del fork y el `replace` de neutrino-blake2b, igual que `build.sh`.
  2. Generar en start10 los `mobile/*_generated.go` con `gen_protos_docker.sh`. Necesita Docker, y los runners macOS de GitHub no lo tienen. Se suben como artefacto o en una rama `ios-mobile` de `Kilombino/lnd` con un `ENGINE` y su sha256.
  3. En macOS: `gomobile init` y `gomobile bind -target=ios,iossimulator -iosversion 15 -tags="$TAGS" -ldflags "-s -w"`.
- **Tamaño:** cada librería estática de Go para iOS ocupa unos 90–120 MB sin comprimir por arquitectura, y el xcframework entero (arm64 dispositivo + arm64/x86_64 simulador) unos 300 MB. Pero App Store solo entrega la arquitectura del dispositivo y el enlazador quita lo que no se usa: la app instalada suele crecer unos **40–60 MB** (Zeus y Blixt llevan LND embebido en iOS en esa misma escala). Es un precedente de que Apple acepta LND embebido.
- **¿Lo compila el CI de GitHub?** Sí:
  - Los runners `macos-14/15` (arm64) traen Xcode, y Go 1.27.3 se instala con `actions/setup-go`.
  - Un `gomobile bind` para iOS tarda unos 20–40 min.
  - Propuesta: un workflow aparte, `lnd-ios.yml`, que solo se ejecuta cuando cambia `lnd-engine/ENGINE`. Compila, sube el xcframework como artefacto o release `lnd-engine-…` con su sha256, y el workflow de la app lo baja y comprueba contra `ENGINE`, como la build Android comprueba el `.aar`.
  - El repo no lleva el binario (sería demasiado grande para git).
- **Límites de segundo plano en iOS y cómo mitigarlos:**
  - **Recibir** pide que el nodo esté en línea. En iOS solo lo está con la app abierta. Unos 30 s más tras salir con `beginBackgroundTask`, que ya se usa en las rondas. Un posible BGProcessing no es fiable para recibir. Mitigación: avisar en la UI de que para recibir hay que tener la app abierta. Las ofertas BOLT12 reutilizables siguen valiendo porque el pagador reintenta. kilo-lsp con factura retenida ya da margen al LSP. **No** hay push de servidor: los pagos entrantes en segundo plano con APNs y extensión de notificación (como Breez) exigen un servidor que despierte la app, y Kilombino no quiere servidores push. Queda como opción futura.
  - **Pagos salientes:** se completan con la app abierta. Si el usuario sale a mitad, `beginBackgroundTask` deja unos 30 s y la reconciliación al volver ya existe en Android ("pagos en segundo plano" de 1.4.3: `Lightning.kt` sigue el pago al reabrir).
  - **Canales y seguridad:** si la app pasa días cerrada, no vigila incumplimientos. Mitigación: **watchtower obligatorio**. Android ya añade la torre de Kilombino (`Lightning.kt:33`) y se pueden añadir las torres propias. BGAppRefresh sincroniza neutrino de forma oportunista. `CSV delay` alto en los canales con kilo-lsp.
  - **Sincronización neutrino:** cada apertura tiene que ponerse al día. Con bloques cada 9–66 min en BLAKE2b es poco, pero la primera sincronización tarda. Se muestra el progreso, como hace Android con `syncHeight`.
  - **Memoria:** LND con neutrino consume unos 150–300 MB. Es aceptable en iPhones de 4 GB o más; en los de 3 GB hay riesgo de jetsam y conviene probarlo.
- **App Store:**
  - Exige cuenta de organización para monederos (ya lo tiene Russo).
  - En export compliance, LND y NIP-44 usan cifrado no exento: revisar `ITSAppUsesNonExemptEncryption` (ya anotado en `docs/APP_REVIEW_NOTES.md`).
  - La app no descarga código: el binario va enlazado.
  - Nombres: Kilojoin y Corecoin, nunca spam o coinjoin.
- **Riesgos:**
  - **gomobile e iOS con cgo:** puede haber que ajustar `-iosversion` y los flags de enlace.
  - **Bitcode / dSYM:** usar `-ldflags "-s -w"` y desactivar bitcode, que Apple ya no exige.
  - **Tamaño de la descarga.**
  - **Revisión de Apple más lenta** con LN embebido.
  - **Sin dispositivo en el CI:** el simulador sí arranca LND, así que se puede probar neutrino contra testnet4 o regtest en el CI, pero los canales reales y la batería solo los puede probar Russo.

### 3.3 Tor
Android usa Arti (`tor-engine/`, Rust). **Hecho en iOS** (1 nov 2026, ver «Estado — Tor» al final): el mismo Arti 2.7.0 como biblioteca estática dentro de la app.

### 3.4 Segundo plano en general
iOS no tiene servicios en primer plano. Para avisos de saldo, moneda perfecta, rondas automáticas y renovación de Ark: `BGAppRefreshTask` (unos 30 s, cuando iOS quiere) y `BGProcessingTask` (minutos, normalmente con el móvil cargando), más notificaciones locales. La UI debe decirlo con honestidad ("en iPhone esto ocurre cuando abres la app o cuando iOS te deja").

---

## 4. Hitos internos → una sola versión 1.0.0

Cada hito es una rama (`parity/1-ui`, …) que se fusiona en `main` con el CI verde y capturas demo comparadas con Android. **No se etiqueta ni se avisa a Russo hasta el hito 5.**

**Hito 1 — Pantallas y navegación (unas 2 semanas)**
1. Barra de 4 pestañas (BTC · Lightning · Kilojoin · Ark), cabecera con la píldora de carteras, salir y bloquear, y modo simple (M).
2. Inicio BTC como Android: tarjeta de escaneo, saldo con hide y cuenta atrás, chips fiat y textos de ayuda (M).
3. Ajustes con la estructura de Android, mostrando los elementos que todavía no existen como deshabilitados (M).
4. Tablas i18n de Android 1.4.4 con `sync-i18n.py` y textos iOS nuevos (S).
5. Capturas demo por pantalla nueva contra `kw-web-parity/raw-android` (S).

**Hito 2 — On-chain completo (unas 2–3 semanas)**
1. **Maduración de coinbase con la regla de 1.4.0** (S). Va primero, porque la regla actual de iOS ya no es correcta tras 979 920.
2. Taproot bc1p, caliente y vigilar, con vectores Schnorr (M).
3. CPFP (S), HISTORY (M), dados (S) y `.kab` interoperable con Android (M).
4. Kilojoin de 1.3.9 a 1.4.4: asientos múltiples, un aviso por ronda, moneda perfecta y pools Taproot, con interop real (M–L).
5. Contactos, PSBT/UR, Silent Payments, vista Corecoin y nodo propio (M cada uno; los que no entren en 1.0 se marcan como "próximamente").
6. Notificaciones locales y BGAppRefresh para avisos de saldo (M). Widget WidgetKit (M, opcional en 1.0).

**Hito 3 — Ark (unas 2 semanas)**
1. Funciones `extern "C"` en kilombino-ark y xcframework compilado en el CI con `ENGINE` y sha256 (M).
2. `Ark.kt` al KMP, palabras en el Keychain, UI de Ark (activar, cartera, recibir, enviar, entrar, renovar, emergencia) (L).
3. Notificación local antes de que caduquen las monedas (S).

**Hito 4 — Lightning (unas 3–4 semanas)**
1. Workflow `lnd-ios.yml` que compila `Lndmobile.xcframework` (protos generados en start10, `ENGINE` y sha256) (M).
2. Interfaz `LndEngine` en el core y su implementación en Swift sobre `Lndmobile` (S).
3. Port de `Lnd.kt`, `Proto.kt`, `LnSeed.kt`, `TxSize.kt`, `Scb.kt` e `Inbound.kt` con vectores (M).
4. `Lightning.kt` y UI: activar, canales, abrir y cerrar, splice, pagar, recibir, BOLT12, kilo-lsp, torre, mover nodo y rescate (L).
5. Puente SHA-256 (`Bridge.kt`) (S).
6. Prueba en el CI: LND en el simulador contra regtest propio o testnet4 (neutrino, abrir canal, pagar), como la interop de Kilojoin (M).

**Hito 5 — Versión única 1.0.0 (unos 3–5 días)**
- README, notas, `APP_REVIEW_NOTES.md` (cifrado, LN embebido, segundo plano), guía de prueba para Russo y tag `v1.0.0`. Release sin binario firmado: Russo compila y firma.

**Qué se puede probar en cada sitio:**
- **CI de GitHub (macOS, simulador):** tests del core con los vectores de Android, interop de Kilojoin, capturas demo de todas las pantallas, arranque de Ark y LND en el simulador contra regtest, compilación de los xcframework.
- **Solo Russo, en dispositivo:** Face ID, cámara, envío real, Kilojoin con fondos, Ark y LN con dinero, segundo plano real (BGTask), memoria y batería de LND, tamaño en App Store y revisión de Apple.

**Estimación total:** unas 9–12 semanas de trabajo efectivo para la versión única. Si Lightning es lo urgente, los hitos 1 y 4 pueden ir primero, con on-chain y Ark detrás, sin cambiar que Russo solo reciba la 1.0.0.

---

## 5. Estado (rama `v1`, 1 nov 2026)

**Etapa 0 — hecha** (`e67fbe0`): maduración de coinbase = Android 1.4.4 (`maturityFor` / `maturesAt` / `mayBeImmature` / `immature`; 6 480 en 973 440–979 918, 25 920 desde 979 919, 100 el resto). Vectores `MaturityVectors.kt` (130 filas) generados con `./gradlew -p tools/android-vectors maturity`, que extrae el bloque literal del `Scanner.kt` de Android (commit f5bef51); `MaturityTest` exige igualdad. Falta mostrar en la UI las monedas inmaduras con su fecha (va con HISTORY, etapa on-chain).

**Etapa 1 — hecha** (`e763f99`):
- Paleta de Android 1.4.4 (paneles azules, naranja BTC, oro Lightning, azul Kilojoin, rojo Ark).
- Cabecera: 🔥 HOT | 👁 WATCH (iOS guarda un monedero a la vez: tocar el otro explica y lleva a ajustes; dos monederos a la vez = pendiente), simple/advanced, ⚙, ⏻ = **bloquear** (iOS no deja salir; desbloqueo con Face ID para monederos calientes), logo. Sin 🫙 JM (Jam pendiente).
- Pestañas BTC · LIGHTNING · KILOJOIN · ARK; Kilojoin es ya una pestaña (Screen.Pools → Home+Tab.POOLS); Lightning y Ark muestran la intro/avisos de Android con el botón apagado y aviso de que el motor llega en la próxima compilación.
- BTC: tarjeta de escaneo, saldo con hide/show, cuenta atrás de refresco, ₿, USD/EUR/▾, direcciones usadas · altura, tipo de dirección, línea de la cadena, RECEIVE/SEND, movimientos, "Cómo se encuentran tus monedas".
- Modo simple real (saldo + RECEIVE/SEND).
- Ajustes en el orden de Android; reales: idioma, refresco (30 s/1/5 min/a mano), Kilojoin on/off, moneda, gap limit ±5, reescaneo, nodo/explorador, zpub + QR, modo calle, cambiar/olvidar, palabras. Visibles pero apagados: Corecoin, avisos en segundo plano, Ark (lleva a su pestaña), Taproot, archivo de copia.
- Tablas i18n de Android 1.4.4 + 26 textos iOS nuevos en 7 idiomas.
- Capturas nuevas en CI: simple, lightning, ark, locked, home@es, lightning@es, home@zh.

**Siguiente (orden acordado): Lightning** — xcframework de LND (lnd-ios.yml), port de `Lnd.kt`/`Proto.kt`/`LnSeed.kt` al core con interfaz `LndEngine` en Swift, pantalla de Lightning; luego on-chain (Taproot, CPFP, HISTORY, dados, .kab, dos monederos, Jam), luego Ark.

## Estado tras la etapa Lightning (1 nov 2026, rama `v1`)

**Hecho y comprobado en CI (run 41268837589, verde: tests, simulador con capturas, Release para dispositivo):**
- **Motor:** `Lndmobile.xcframework` del mismo LND que Android 1.4.4 (v0.21.4-beta-blake2b.21.1 + neutrino-blake2b con NODE_BLAKE2B). Fuentes en `Kilombino/lnd` rama `ios-engine-21.1` (b91ef20c1, con los `mobile/*_generated.go` generados en start10). `lnd-ios.yml` lo compila en macos-15 (37 min): 280 MB (dispositivo arm64 95 MB), zip 77 MB, sha256 `e5faf8ba…` fijado en `lnd-engine/ENGINE` (run 41263535644). `scripts/fetch-lnd-engine.sh` lo baja del artefacto y comprueba el hash; nada binario en git. La app Release sin firmar pesa 123 MB.
- **Core KMP (`core/.../ln`):** `Lnd.kt` (todo el RPC de Android: wallet, canales, splice, pagos, BOLT 12, facturas, torre, SCB), `Proto.kt`, `TxSize.kt`, `LnSeed.kt` (derivación de claves), `Inbound.kt` (kilo-lsp), sobre `LndEngine`/`LndHost`. Vectores del propio código Android (`./gradlew -p tools/android-vectors ln`): claves de nodo, bytes de las peticiones, lector protobuf, tamaños/CPFP; `LnTest` 7/7.
- **Arranque real en el simulador** (`KW_DEMO=ln-live`, cartera "abandon … about"): LND arranca, crea la wallet, sincroniza cabeceras por neutrino hasta el bloque 979 955 en ~3 min contra los peers BLAKE2b (Kilombino, PyBLOCK, mempool.guide…), filtros hasta 979 001 y empieza la recuperación desde la activación 961 640. La pubkey del nodo (02faeca8…) coincide con `LnSeed.nodePubkey` (test).
- **Pantalla LIGHTNING** como la de Android: intro y avisos, arrancando (bloque X de Y), fallo (copiar log), nodo con saldo, FUND / CHANNEL / INVOICE / PAY, oferta BOLT 12 reutilizable, canales y cierre cooperativo, movimientos, canal entrante de Kilombino (kilo-lsp, con la comprobación de la factura en el propio nodo), panel "En iPhone" con los límites. 7 idiomas. Capturas: `07-lightning`, `08-ln-starting`, `09-ln-running`, `10-ln-failed`, `27/28-…-es`, `32-ln-live` (en `/mnt/datos2/kw-ios-ci/v1-41268837589/screenshots`).
- **Ciclo de vida iOS:** el nodo arranca al abrir la app (si estaba activado), `beginBackgroundTask` durante un pago, pagos en vuelo seguidos al volver (`followInFlight`), contraseña de la wallet en el Llavero sin Face ID (AfterFirstUnlockThisDeviceOnly), directorio `Application Support/lnd` excluido de copias.
- De paso: "bc1q" con el texto de Android (traducido) y botones de Kilojoin en azul.

**Bloqueos / pendiente:**
- **Watchtower:** las torres que conocemos (Kilombino `033f366c…`, Roman, logosnaut) son solo onion y la app iOS no tiene Tor → no se puede añadir por defecto. Opciones: (a) exponer la torre de Kilombino por clearnet (puerto en start10/AirVPN: **decisión de Kilombino**, no se ha tocado nada), o (b) meter Tor en iOS (Arti/Tor.framework, trabajo grande). Hoy la pantalla lo avisa.
- **Modo demo por variable de entorno** (`KW_DEMO`), no por argumento: LND añade los argumentos del proceso a sus flags y para con "unknown flag". Russo no debe poner argumentos de lanzamiento en el esquema de Xcode.
- **Para Russo:** el xcframework lo baja `scripts/fetch-lnd-engine.sh` con `gh` (artefacto, caduca a los 90 días). Para la 1.0.0 hay que adjuntarlo a la release o que lo compile él con `lnd-engine/build-ios.sh`.
- **Sin portar todavía de Lightning:** splice (redimensionar), cerrar varios / forzado, acelerar (CPFP) aperturas y cierres, pagar on-chain desde el nodo, puente SHA-256 (`Bridge.kt`), mover el nodo (`LnMove.kt`), rescate por SCB (`Scb.kt`/`LndRescue.kt`, necesita XChaCha20-Poly1305 en el core), exportar el SCB, alias/comisión máxima/peers en Ajustes, copia `.kab` con el SCB, avisos locales.
- **Sin probar:** canales, pagos y cobros reales (solo dispositivo con fondos), memoria/batería en iPhone, revisión de Apple.

**Siguiente:** on-chain (Taproot, CPFP, HISTORY, dados, `.kab`, dos monederos, Jam) y Ark; y cerrar lo pendiente de Lightning antes de la 1.0.0.

## Estado tras la etapa on-chain (1 nov 2026, rama `v1`)

**Hecho** (commits `fc0087d` core Taproot/CPFP/dados/.kab, `91f0de7` core PSBT/UR, `9865783` app; tests JVM verdes; CI de iOS en la entrega):
- **Taproot (BIP-86):** `ScriptType.P2TR`, direcciones bc1p (TapTweak con libsecp256k1), `TxBuilder` firma ruta de clave (BIP-341 o unificada 0x21 con script type 2) junto a P2WPKH; `Signer` deriva m/86' para monedas Taproot y las comprueba contra la cuenta; tarifas a 57,75 vB por entrada Taproot. El monedero caliente deriva su cuenta BIP-86 al crearse/restaurarse (o una vez al activarlo con Face ID si viene de 0.4.x), se escanea y se gasta junto a la BIP-84; Ajustes elige si las direcciones nuevas (recibir y cambio) son bc1q o bc1p. Un xpub plano se puede vigilar como Taproot (tipo guardado aparte, como Android). Vectores del código Android (`./gradlew -p tools/android-vectors onchain` → `OnchainVectors.kt`): cuenta y direcciones, gastos solo Taproot y mixtos, unificados y BIP-341 puros, byte a byte; vector oficial BIP-86 ("abandon … about" → bc1p5cyxnu…).
- **Dos monederos a la vez:** caliente + solo lectura lado a lado; la píldora 🔥 HOT | 👁 WATCH cambia de verdad (si falta uno, ofrece añadirlo); quitar uno deja el otro. Migración del monedero único de 0.4.x.
- **CPFP:** `CpfpPlanner` + `Scanner.cpfpParent` (tamaño del padre contado por tipo de entrada, nunca fiándose de un witness inflado; antepasados sin confirmar rechazados). Botón "⚡ speed up" en la fila y en el detalle de un cobro pendiente; misma revisión y firma.
- **Dados:** `Bip39.entropyFromDiceRolls` (SeedSigner, 50/99 tiradas) + `DiceCheck`; pantalla de 6 botones con deshacer/borrar lejos de los dados, aviso si las tiradas parecen inventadas. Vectores del código Android (palabras y avisos).
- **Archivo de copia `.kab`:** `Kab` en Kotlin puro (AES-256-GCM, PBKDF2-HMAC-SHA256 × 310 000, gzip con bloques "stored" al escribir e inflate completo al leer), compatible byte a byte con `ark/ArkBackup.kt`: la herramienta de vectores abre con el código Android un archivo hecho aquí, y el test abre uno hecho por Android. Guardar con contraseña (Face ID para leer las palabras) por el selector de documentos (`FileBridge.swift`); restaurar desde la bienvenida. Los ajustes viajan con las claves de Android (`fiat`, `refresh_open_s`, `coinjoin_enabled`) más `ios_*`; nunca los servidores. Un archivo con Ark restaura palabras y ajustes y avisa de que Ark queda fuera.
- **PSBT/UR (firmar fuera):** `Psbt` (BIP-174, todas las entradas piden 0x21) y `Ur` (ur:crypto-psbt) portados; vectores Android para bc1q, SegWit anidado y Taproot (PSBT exportado idéntico, firmado → misma transacción, firma alterada rechazada, mismas partes QR). Monedero de solo lectura: SEND → revisión → "SIGN ELSEWHERE (PSBT)" con QR animado (300 ms/parte) o base64; vuelta por cámara (el escáner sigue leyendo un QR animado, `scanQrParts`) o pegando. `TxBuilder` serializa entradas SegWit anidadas.
- **Kilojoin HISTORY:** pestañas POOLS · IN PROGRESS (n) · HISTORY; las rondas guardan `sentAt`/`endedAt` (como Android 1.4.0) y la tarjeta dice "📅 Sent … · confirmed …" (UTC), a qué monedero fueron las salidas y sus direcciones. Las monedas Taproot quedan fuera de los pools.
- 63 textos nuevos en 7 idiomas; capturas nuevas: dice, backup, cpfp, receive-taproot, settings-taproot, pool-history, psbt (y @es).

**No hecho en esta etapa (anotado):**
- **Asientos múltiples (ownSeats) y pools Taproot:** el `PoolSession` de iOS sigue en el protocolo de 1.3.9; traer 1.3.9 → 1.4.4 necesita su propia interop contra Android (L). Mientras, iOS no ofrece pools bc1p ni varios asientos.
- **Monedero Jam (🫙 JM):** no portado.
- **Segundo plano:** rondas automáticas/perpetuas y moneda perfecta dependen de un servicio en primer plano en Android; en iOS solo con la app abierta o BGProcessing (oportunista). Avisos de saldo con la app cerrada = BGAppRefresh + notificaciones locales; widget = extensión WidgetKit. No hechos: cada uno es M y ninguno es imprescindible para la 1.0.0; quedan para después de Ark.
- **Monedas coinbase inmaduras con fecha** en la UI: la regla está, falta mostrarlas.
- **Vista Corecoin, nodo propio por RPC, contactos, Silent Payments:** sin empezar.

**Siguiente:** Ark; luego lo pendiente de Lightning (splice, SCB/rescate, puente…) y, si cabe, ownSeats/pools Taproot.

## Estado — etapa Ark (1 nov 2026, rama `v1`)

**Hecha.** CI verde run 41287036189 (capturas en /mnt/datos2/kw-ios-ci/v1-41287036189/). Commits `af1fb11` (core), `0b9706a` (app/Swift/CI), `cb0aef7` (pin del motor), `ea72121`, `a805f2f` y `6ecbfbd` (arreglos); motor en `Kilombino/paperclip-wallet-app` rama `kilombino-ios` (`10a1a0c` + `fe7cccd`).

- **Motor:** `kilombino-ark` con `kilombino_ark_start/stop/free` en C, compilados solo para iOS (`#[cfg(target_os = "ios")]`: la `.so` de Android no cambia; Android sigue en 349d199). `kilombino-ark/build-ios.sh` pide a rustc un `staticlib` con `cargo rustc --crate-type` (el Cargo.toml queda cdylib) para `aarch64-apple-ios`, `aarch64-apple-ios-sim` y `x86_64-apple-ios`, y lo empaqueta en `KilombinoArk.xcframework` con cabecera y module map (`import KilombinoArk`). Workflow `ark-ios.yml` (18 min en macos-15, run 41278303674): 37 MB dispositivo, 71 MB simulador, zip 40 MB, sha256 `e1be8ad8…` fijado en `ark-engine/ENGINE`; `scripts/fetch-ark-engine.sh` lo baja y lo comprueba en el CI de la app.
- **Core KMP (`core/.../ark`):** `Ark.kt` = `ark/Ark.kt` de Android 1.4.4 (todas las llamadas REST: saldo, direcciones, board con control de monedas, envíos con coste exacto previo y tope `max_total_sat` / re-presupuesto, depósito on-chain, CPFP, renovaciones que apartan monedas rechazadas, retiradas, salida de emergencia, Lightning vía el servidor Ark con oferta BOLT12, actividad, log). Interfaces `ArkEngine`/`ArkHost`. `ArkDerive` con vectores del código Android (`./gradlew -p tools/android-vectors ark`), `ArkTest` 4/4.
- **App:** pestaña ARK como Android (ArkScreen + ArkWords + ArkEmergency): avisos, ACTIVATE (palabras propias derivadas, Face ID una vez), restaurar desde palabras (pregunta 1.1.4 o posterior si son las del monedero), saldo (Ark / depósito / Lightning liquidándose / caducidad), RECEIVE (Ark, depósito, factura LN seguida hasta cobrada, oferta BOLT12), SEND y MOVE INTO ARK con REVIEW COST antes de Face ID y control de monedas, RENEW con selección de monedas, actividad con detalle, palabras, salida de emergencia, copiar log, panel "On iPhone".
- **iPhone:** palabras de Ark en un ítem del Llavero sin Face ID (after-first-unlock, solo este dispositivo); el motor arranca al abrir la app y renueva lo que caduque en < 1 008 bloques; notificación local 3 días antes de la próxima caducidad; `BGProcessingTask` `com.kilombino.kilowallet.ark-renew` (Info.plist: `UIBackgroundModes processing`, `BGTaskSchedulerPermittedIdentifiers`, `NSAllowsLocalNetworking` para http a 127.0.0.1); carpeta del motor fuera de copias.
- **Probado en simulador (CI):** `ark-live` = motor real, crea un monedero desechable en ark.paperclippool.xyz (sin fondos), recuperación del buzón (0 monedas), demonio arrancado; capturas `ark`, `ark-ready` (+es), `ark-live`.
- **Sin probar:** nada que mueva dinero (board, pagos Ark/LN, renovar, salir), el BGProcessingTask real, notificaciones, dispositivo.
- **No hecho:** copia `.kab` con la base de datos de Ark (el archivo sigue llevando solo palabras y ajustes: las monedas vuelven por el buzón del servidor), contactos, comprobación cruzada de la cadena (ArkCrossCheck), y subir PoolSession a Kilojoin 1.4.4 (ownSeats/pools Taproot): queda para la siguiente etapa.

**Para la 1.0.0 queda:** restos de Lightning (splice, rescate SCB, puente, mover nodo, watchtower pendiente de Kilombino), Kilojoin 1.4.4 (ownSeats, pools Taproot, rondas automáticas), Jam, widget/avisos con la app cerrada, `.kab` con Ark, probar en dispositivo con dinero, y adjuntar ambos xcframework a la release (los artefactos caducan a los 90 días).

## Estado — Kilojoin 1.4.4 + restos de Lightning + coinbase inmaduras (1 nov 2026, rama `v1`)

**Hecha.** CI verde run 41292913208 (capturas en /mnt/datos2/kw-ios-ci/v1-41292913208/). Commits `51f21f6` (Kilojoin core), `d127f28` (app Kilojoin + inmaduras), `1384276` (SCB core), `2d77021` (app Lightning).

- **Kilojoin al protocolo de Android 1.4.4:** `Env.ownSeats` + `distinctPeople` (los asientos propios cuentan como una persona en cierre, votos, aviso de caducidad, "again"; `OWN_SEATS_ONE`), `State.seatId/joinAfter` con `keyOf/poolOf`; otro asiento en un pool donde ya estás (espera aleatoria 20–90 s, conexión propia); pools Taproot (BIP-86 ruta de clave, Schnorr) para unirse y abrir desde la cuenta m/86', salidas y cambio bc1p; pagos por pool a bc1p si hay cuenta Taproot. El resto de 1.3.9→1.4.4 (sentAt/endedAt, un aviso por ronda) ya estaba. Nada del protocolo en la red cambió (v1–v5 iguales).
- **Vectores:** KilojoinVectors regenerados de Android f5bef51 con rondas Taproot `v3tr`/`v4tr` (firmas Schnorr de Android verificadas y tx cruda idéntica); `SeatsTest` 5/5.
- **Interop real contra Android 1.4.4** (regtest /mnt/datos2/tmp-cjtest2, relay.kilombino.com): v4 y v5 en ambos sentidos, Taproot v3 (pool iOS, entran Android e iOS), Taproot v4 (pool Android, iOS con dos monedas), y dos asientos iOS en un pool Android de mínimo 3 (rechaza cerrar con solo sus asientos; con un tercero cierra) — todas OK, byte a byte. Log: /mnt/datos2/kw-ios-ci/interop-1.4.4.log.
- **Lightning:** `XChaCha20Poly1305` (Poly1305 en limbs de 26 bits, vector RFC 8439) y `Scb` en el core, con vectores del backup que hizo el código de LND (fixture de Android) leído por el lector Android (`LnVectors.SCB/XCHACHA`, `ScbTest` 4/4). App: exportar SCB, restaurar SCB (se abre antes con las claves del nodo —Face ID— y lista sus canales), RESIZE (splice in/out con estimación previa; protocol.splice ya estaba en el config), cierre a dirección o forzado, SPEED UP de un cierre cooperativo (CPFP), pagar on-chain desde el nodo con control de monedas, límite de comisión de enrutado y nombre del nodo. Textos de Android (sus traducciones aplican).
- **Coinbase inmaduras:** `Scanner.immature` (Android 1.4.0): bajo el saldo "of which … sats are mined rewards still locked …" con bloque y fecha estimada por el ritmo de las últimas 144; cada movimiento que creó una recompensa bloqueada lleva su línea "⛏ mined reward · matures at block … (~fecha)". Captura `immature` (demo: dos recompensas inventadas sobre el monedero de prueba, por eso el saldo dice 0).

**No hecho / anotado:**
- **Rescate de LND anterior a la bifurcación (`LndRescue`: barrido por claves del aezeed y repetición de cierres SHA-256):** necesita Aezeed/Aez/Scrypt en el core y un cliente Electrum a la cadena SHA-256; es una herramienta de recuperación rara, no del día a día: queda fuera de la 1.0.0 salvo que Kilombino la pida. El rescate normal por SCB (darle el backup al nodo) sí está.
- **Cerrar varios canales a la vez y "a mi monedero BTC"** en el cierre/splice-out: iOS ofrece nodo o dirección pegada (falta leer una dirección fresca del monedero desde la pestaña LN).
- **SPEED UP de aperturas/pagos on-chain del nodo (`SpeedUpTxDialog`) y lista de transacciones on-chain del nodo:** no portados.
- **Puente SHA-256 (`Bridge.kt`), mover el nodo (`LnMove.kt`), `.kab` con el SCB, watchtower (pendiente de Kilombino):** sin hacer.
- **Rondas automáticas/perpetuas y moneda perfecta:** dependen de segundo plano (ver arriba).
- **Sin probar:** splice/cierre/CPFP/SCB con canales reales (solo dispositivo o regtest con LND iOS), Taproot y asientos múltiples desde la app (probado el core por interop, no la UI con fondos).

**Para la 1.0.0 queda:** probar en dispositivo con dinero (Kilojoin bc1q/bc1p, Lightning, Ark), puente/mover nodo si se quieren, Jam, avisos con la app cerrada/widget, `.kab` con Ark y SCB, watchtower, y adjuntar los dos xcframework a la release.

## Estado — Tor (Arti) dentro de la app (1 nov 2026, rama `v1`)

**Motor** (`tor-engine/`): el mismo Arti que Android 1.4.4 (tag `arti-v2.7.0`, commit `ce8bc6e`, arti 2.7.0 / arti-client 0.47.0, mismas features menos `harden`, que solo endurece el proceso del programa `arti` y aquí actuaría sobre toda la app). iOS no deja lanzar programas, así que `tor-engine/kilombino-tor` (crate propio, `Cargo.lock` sacado del de arti: todas las versiones iguales) ejecuta el proxy SOCKS de arti como biblioteca, con los ajustes del `arti.toml` de Android: un puerto SOCKS en 127.0.0.1, sin DNS, `.onion` permitidas, `cache/` y `state/` en `Application Support/arti` (fuera de copias), `dangerously_trust_everyone`, log `info` con registro seguro en `Application Support/tor.log`, y la línea "Sufficiently bootstrapped; proxy now functional." como el programa. Runtime tokio propio para que parar Tor libere de verdad el bloqueo de `state/` (comprobado: sin eso el segundo arranque iba en solo lectura). C API: `kilombino_tor_start/stop/state/free`.
- `build-ios.sh`: `cargo rustc --crate-type staticlib` (LTO, un objeto) por porción y `ld -r -exported_symbols_list` para dejar globales solo las 4 funciones (convive con la biblioteca Rust de Ark sin choques de símbolos). OJO: la std precompilada de Rust 1.98 trae bitcode de LLVM 22 en compiler_builtins y el `nm` de Xcode 16.4 se para con él: las comprobaciones usan `nm --no-llvm-bc` (el enlazador solo usa el código máquina, igual que con Ark). `tor-ios.yml` (15 min), pin run 41305757467, sha256 `f24fa664…`, 14 MB dispositivo / 28 MB simulador, zip 15 MB. `scripts/fetch-tor-engine.sh` en el CI.
- Probado en start10 (Linux, `examples/live.rs`): arranque en 25 s (3 s con caché), conexión SOCKS a la torre `b44c2u…onion:9911`, al nodo `eu2pnt…onion:9735` y al puente `li7nwm…onion:80`; parar y arrancar en el mismo puerto; un `guards.json` con ceros reproduce "corrupted data in persistent state" (Arti muere tras escuchar, durante el bootstrap).

**Core** (`core/.../tor/Tor.kt`, port de `chain/Tor.kt` de Android 1.4.4): `repairState` (1.2.7-beta6: solo objeto/array JSON), borrado de `state/` y **rearranque inmediato** cuando Arti dice "corrupted data in persistent state" (también si muere después de escuchar), puerto fijo durante toda la ejecución (LND lo tiene en su configuración; uno nuevo solo si está ocupado), `onForeground` (iOS suspende la app y puede quitarle el puerto: si no contesta un saludo SOCKS5, se rearranca en el mismo puerto), log recortado a 32 kB pasados 256 kB. `TorStateTest` 6/6 (el test de Android + estado corrupto, puerto, primer plano, sin Tor).

**Qué va por Tor** (lo mismo que Android 1.4.4):
- **LND**: Tor arranca antes que el nodo; `tor.active`, `tor.socks=127.0.0.1:<puerto>`, `tor.skip-proxy-for-clearnet-targets=true` (los peers clearnet siguen directos, como Android). Torre de Kilombino `033f366c…@b44c2u…onion:9911` añadida una vez con el nodo ya arriba (como Android), y el onion del nodo de Kilombino como alternativa al conectar.
- **"Solo por Tor"** (ON por defecto, como Android desde 1.2.7-beta6): en Android solo afecta a los avisos de Telegram (que iOS no tiene); en iOS hace que **Lightning espere a Tor** (hasta 3 min) y, si no llega, se para diciendo por qué (con la última línea de Arti) en vez de arrancar sin Tor en silencio (fallo de Android 1.2.7-beta4). Apagado: como Android, 1 min y sin Tor, y la pantalla avisa de que no hay torre.
- **Electrum**: un `.onion` siempre por Tor; "Conectar al servidor a través de Tor" (OFF por defecto, como Android) lleva cualquiera. iOS 17+ (proxy SOCKS5 de Network.framework); onion en 50001 en claro primero, como Android. Si Tor no arranca no se envía nada.
- **Relay de Kilojoin, kilo-lsp y el explorador (FUND)**: por Tor cuando ya está en marcha, como Android; cada asiento y cada salida entregada con su propio circuito (usuario SOCKS `kj-…`/`kj-out-…`, como RelayClient de Android).
- **No aplica en iOS** (no portado): bot de Telegram (fallback onion → https de 1.2.7-beta7), puente SHA-256 (onion `li7nwm…`), nodo propio por RPC. Ark no usa Tor (tampoco en Android).

**Ajustes**: bloque Tor (estado: no arrancado / conectando + última línea / activado con puerto / parado), "Solo por Tor", "VER EL REGISTRO DE TOR" (pantalla con las últimas 40 líneas y COPIAR); "Conectar al servidor a través de Tor" en "Nodo y explorador". 17 textos nuevos en 7 idiomas (scripts/i18n-ios; los de Android —"Solo por Tor", "VER EL REGISTRO DE TOR"…— vienen de sus tablas).

**Segundo plano**: Tor arranca bajo demanda (Lightning, onion, Electrum por Tor) antes que LND; al volver a primer plano se comprueba el puerto y se rearranca si hace falta. Sin servicio en segundo plano (iOS).

**Probado en CI** (run 41306510714 verde: tests, simulador, Release para dispositivo enlazando Tor + Ark + LND sin choques de símbolos; capturas en /mnt/datos2/kw-ios-ci/v1-41306510714/):
- `tor-live`: Arti real en el simulador, bootstrap en ~8 s, y el escaneo del monedero con "Conectar al servidor a través de Tor" (fulcrum por Tor con TLS y huella) terminó en el bloque 980 019. Captura `48-tor-live` (Ajustes: Tor activado con su puerto, "Solo por Tor").
- `tor-log`: `49-tor-log` (pantalla del registro).
- `ln-live`: LND con Tor: torre `033f366c…@b44c2u…onion:9911` añadida y activa (ListTowers), y conexión al nodo de Kilombino **por su onion** (`eu2pnt…onion:9735`, "Established outbound connection" en el log de LND). Captura `50-ln-live`.
- Logs: `reports/screenshots-logs/{tor-tor-live,tor-ln-live,lnd-live,ln-live}.log`.

**Sin probar**: dispositivo real (memoria/batería de Arti + LND, suspensión real y `onForeground`), Electrum a un `.onion` (no hay servidor BLAKE2b onion a mano; el código es el de Android), relay de Kilojoin por Tor con aislamiento por usuario SOCKS (iOS 17 `ProxyConfiguration.applyCredential`; en iOS 16 las claves CFNetwork), iOS 16 en general (Electrum por Tor exige 17).

**Pendiente menor**: la pantalla del registro muestra las 40 últimas líneas desde arriba (en la captura, el final queda bajo el pliegue); "Bootstrap status" puede escribirse después de "Sufficiently bootstrapped" (orden de eventos de Arti), sin efecto en el estado.

## Estado — copia completa, motores en release, puente, mover el nodo, avisos (1 nov 2026 noche, rama `v1`)

- **`.kab` completo** (99d9e5e, 1f35e7d): el archivo lleva el monedero Ark (config.toml, db.sqlite y su WAL, palabras derivadas → versión 4 con `"ark_words": "derived"`; mismas palabras → 1/2) y en `app.lightning` el cumpleaños y el SCB de LND, exactamente como `ArkBackup`/`AppBackup` de Android 1.4.4. Vectores en los dos sentidos (`./gradlew -p tools/android-vectors onchain`): el código Android abre un archivo completo hecho en iOS (Ark + Lightning) y `OnchainTest.wholeWalletFileFromAndroidOpensHere` abre el de Android. En el iPhone: al guardar, Ark se para, se copian sus archivos y vuelve a arrancar; al restaurar, se ponen sus archivos con nuestros servidores (nunca los del archivo), sus palabras y se arranca; el SCB queda guardado y se le da al nodo al activar Lightning (`initWallet` con `channel_backups`). CI `ark-live` hace la vuelta completa con el motor real.
- **Motores en release** (b33a0ae): pre-release **v1.0.0-rc1** https://github.com/Kilombino/kilowallet-ios/releases/tag/v1.0.0-rc1 con `Lndmobile/KilombinoArk/KilombinoTor.xcframework.zip` + `SHA256SUMS` (mismos sha256 que los `ENGINE`). `scripts/fetch-engine.sh lnd|ark|tor` descarga de la release (curl, sin cuenta ni `gh`) y comprueba el sha256; el artefacto del workflow queda de reserva.
- **Registro de Tor** abre y sigue al final (1f35e7d).
- **Puente SHA-256** (d5e4e41): `core/ln/Bridge.kt` = Bridge.kt de Android (precio, cotización con prueba de trabajo, comprobaciones del hold invoice, prueba con la preimagen) + `BridgeTest` de Android. En PAY, una factura SHA-256 ofrece el puente con su precio. Por https: Android prueba antes el onion si Tor corre, pero en iPhone eso es http plano y App Transport Security lo rechaza salvo excepción que App Review pregunta.
- **Mover el nodo** (4de452d): formato `KWLNMOVE2` de Android (scrypt 2^16/8/1 + AES-GCM + zip; v1 PBKDF2 también abre), `LnMoveTest` contra el zip y AES-GCM del JVM en los dos sentidos (lo que hace `LnMove.export/import` de Android), scrypt RFC 7914. App: MOVE TO ANOTHER PHONE (para el nodo, empaqueta, comprueba, guarda y lo borra aquí; marca "moving" para no rearrancarlo solo) y MOVE IN FROM ANOTHER PHONE en la intro. Zip propio (escribe stored, lee stored/deflate por el directorio central); inflate pasa a un buffer de bytes (con lista de bytes era inviable para varios MB).
- **Avisos con la app cerrada** (247f99b): BGAppRefreshTask `com.kilombino.kilowallet.watch` (Info.plist: `fetch` + identificador) sobre las direcciones guardadas tras cada escaneo; notificación local "Nuevo ingreso en la mempool / Recibido / Confirmado / Enviado" en el idioma de la app. Interruptor real en Ajustes (apagado por defecto: hay que dar permiso). No cuando el servidor va por Tor (no da tiempo a arrancarlo en ~30 s y no se envía sin él). iOS decide cuándo: sin intervalo fijo.
- **Enlace con Xcode 26** (f079a76): el archivo de release (macos-26) fallaba con "Too many personality routines for compact unwind" (dos `rust_eh_personality` —Ark y Tor— + C++ + ObjC). `-Wl,-no_compact_unwind` en OTHER_LDFLAGS: ARCHIVE SUCCEEDED en run 41360414547. `ios-release.yml` ahora baja los tres motores (y `actions: read`) y su SHA256SUMS se llama `Kilowallet-<v>-SHA256SUMS` para no pisar el de los motores. OJO: la etiqueta v1.0.0-rc1 apunta a 1f35e7d, sin ese arreglo: su archivo no enlaza con Xcode 26; hace falta una rc2 (decisión de Kilombino; la rc1 no se mueve).
- **No hecho**:
  - **LndRescue** (rescate de un LND anterior a la bifurcación: aezeed, claves de canal, cierres forzados con CSV, repetir cierres SHA-256): grande y solo para nodos de antes del 1 sept 2026; necesita un cliente Electrum de la cadena SHA-256 (el core rechaza cabeceras de 80 bytes). Sí se puede en iOS; queda fuera por tamaño/riesgo, Android lo cubre.
  - **Jam (JoinMarket, vigilar y gastar bond)**: ~1 400 líneas en Android; se puede en iOS, sin empezar.
  - **Widget**: WidgetKit es otra extensión (target nuevo en el proyecto) y, para el saldo, un App Group de la cuenta de Russo; uno solo de precio/bloque no necesita App Group. Sin empezar.

**Corrección (2 nov 2026):** `-Wl,-no_compact_unwind` enlazaba con Xcode 26 pero dejaba las excepciones de Kotlin/Native (desenrollado C++) sin información de unwind: la app moría en la primera (las capturas en vivo de 6ea299d no llegaban a "ready"). Quitado. Tor pasa a **framework dinámico** embebido (`KilombinoTor.framework`, @rpath, solo exporta `kilombino_tor_*`): su `rust_eh_personality` vive en su dylib y el binario de la app vuelve a tener 3 rutinas. `tor-ios.yml` run 41369494122, sha256 `d39a9f4b…`, zip 12 MB; en el proyecto: xcframework en Frameworks + "Embed Frameworks" (CodeSignOnCopy). CI verde **run 41370674251** (capturas `/mnt/datos2/kw-ios-ci/v1-41370674251/`: 47 ln-bridge, 48 ln-move, 49 settings-notify, 50 ln-bridge-es, 51 ark-live —vuelta del `.kab` con el motor Ark real: archivo 366 564 B, db 274 432 B, derivadas, Ready tras restaurar—, 52 tor-live —Electrum por Tor Done, 980 092—, 53 tor-log al final, 54 ln-live —torre activa y nodo de Kilombino por onion—). Archivo de release con Xcode 26 (macos-26) **ARCHIVE SUCCEEDED** en run 41370676390 (falló solo la subida, despachado con tag=v1 a propósito para probar el enlace).

**Release:** v1.0.0-rc1 (tag en 1f35e7d, motores estáticos) no enlaza con Xcode 26 y su KilombinoTor.zip es el estático: el `.ipa`/`.xcarchive` de rc1 no se puede generar sin mover la etiqueta. Falta decidir una **v1.0.0-rc2** sobre la rama actual (con el Tor dinámico adjunto); hasta entonces `tor-engine/ENGINE` no tiene `release=` y el Tor se baja del artefacto del run (caduca a los 90 días).

## v1.0.0-rc2 (2 nov 2026)

- **LND en Lightning Fork v0.21.4-beta-blake2b.21.2**: rama `Kilombino/lnd` `ios-engine-21.2` (cae303b36 = tag f782c8fb3 + el commit móvil de 21.1; ningún .proto cambió, mismos `*_generated.go`). `lnd-ios.yml` run 41375973088, sha256 `e135aeaa…`. 21.2 arregla RestoreChannelBackups por RPC (hasta 21.1 rechazaba todo backup "de otra cadena"). El `.kab` da el SCB en `initWallet` (channel_backups), que ya iba en 21.1; el panel "Restore channels from an SCB" usa el RPC: arreglado con 21.2. CI `ln-live` llama al RPC con el backup propio del nodo (sin canales): OK; con canales reales no se ha probado.
- CI verde **run 41379914979** con LND 21.2 (log: version=0.21.4-beta-blake2b.21.2), capturas en `/mnt/datos2/kw-ios-ci/v1-41379914979/`.
- **Pre-release v1.0.0-rc2** (tag en 6f5d263): https://github.com/Kilombino/kilowallet-ios/releases/tag/v1.0.0-rc2 — motores (LND `e135aeaa…`, Ark `e1be8ad8…`, Tor dinámico `d39a9f4b…`) + `SHA256SUMS`, y del `ios-release.yml` (run 41384792291, Xcode 26, por el push del tag) `Kilowallet-1.0.0-rc2-unsigned.ipa` (`981646cd…`, lleva `Frameworks/KilombinoTor.framework`), `…unsigned.xcarchive.zip` (`14b2decd…`) y `Kilowallet-1.0.0-rc2-SHA256SUMS`. rc1 queda como estaba con una línea "superseded by rc2". Los `ENGINE` de la rama apuntan a `release=v1.0.0-rc2`.

**Relay de Kilojoin por su onion** (33ec5e8, para la próxima rc): con Tor en marcha, `ws://pf7d47ay…onion` primero (ws plano por Network.framework con el proxy SOCKS de Tor: App Transport Security rechaza ws:// en URLSession pero no aplica a Network.framework; iOS 17+) y si no conecta `wss://relay.kilombino.com` por Tor; error solo si fallan los dos; sin Tor, igual que antes; aislamiento por asiento/salida (usuario SOCKS) en los dos caminos; los eventos siguen nombrando el relay clearnet. `RelayRouteTest` 4/4. CI verde run 41441652804: `KW tor-live: pool list through Tor via onion: 0 pool(s)` (conexión y suscripción por el onion en el simulador; no había pools abiertos).
