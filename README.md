# Impresora ESC/POS Virtual

App Android que se comporta como una **impresora térmica de tickets en red** (ESC/POS por TCP, puerto 9100)
y muestra en pantalla cómo quedaría cada ticket. Sirve para probar apps de TPV, comandas, etc. sin impresora física.

## Descarga

Cada push a `main` compila el APK con GitHub Actions y lo publica en **[Releases](../../releases/latest)**.

## Uso

1. Abre la app: arranca el servidor y muestra la IP, por ejemplo `192.168.1.37:9100`.
2. En tu app, configura una impresora de red / RAW / ESC/POS con esa IP y puerto.
   Si tu app corre en el mismo móvil, usa `127.0.0.1:9100`.
3. Cada **corte de papel** (GS V, ESC i/m), cierre de conexión o 1,2 s sin datos genera un ticket nuevo.

La pantalla solo muestra los tickets, en vertical. **Mantén pulsado un ticket** para ver sus comandos,
el volcado hex, compartirlo o abrir el resto de opciones (dirección, ajustes, simular estado, ticket de prueba…).

En cada ticket puedes ver la **lista de comandos interpretados**, el **volcado hex** y compartirlo como PNG, `.bin` o `.txt`.
También puedes abrir archivos `.bin`/`.prn` capturados.

## Qué emula

- Texto: fuentes A/B, negrita, subrayado, inverso, tamaños GS ! (hasta 8×8), ESC !, alineación, márgenes,
  tabulaciones, posición absoluta/relativa, interlineado, color rojo, boca abajo.
- Páginas de códigos (ESC t): PC437, 850, 852, 858 (€), 860, 863, 865, 866, WPC1252, ISO 8859-15;
  juegos internacionales ESC R (España I/II, Latinoamérica…), modo kanji/chino (GB18030, Shift-JIS) y opción UTF-8.
- Imágenes: ESC \* (8 y 24 puntos), GS v 0, GS ( L / GS 8 L (búfer y NV), GS \* / GS /, FS q / FS p.
- Códigos de barras GS k: UPC-A/E, EAN-13/8, CODE39, ITF, CODABAR, CODE93, CODE128 (con {A/{B/{C), con HRI.
- Códigos 2D GS ( k: QR, PDF417, DataMatrix, Aztec.
- Corte total/parcial, apertura de cajón (ESC p, DLE DC4), pitidos.
- **Respuestas de estado**: DLE EOT 1-4, GS r, GS I, ESC v/u, ASB (GS a) y GS ( H. Desde la app puedes simular
  *sin papel*, *poco papel*, *tapa abierta*, *cajón abierto* y *offline*.
- Se anuncia por mDNS como `_pdl-datastream._tcp` para apps que buscan impresoras en la red.

## Compilar en local

Abre el proyecto con Android Studio, o con Gradle 8.14: `gradle assembleRelease`.

## Firma

Sin configurar nada se firma con `app/debug.keystore` (incluida, para que el APK de cada release se pueda instalar
encima del anterior). Para usar una clave propia, crea estos secretos del repositorio:
`SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.
