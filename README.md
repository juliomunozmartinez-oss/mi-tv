# Mi TV

Reproductor IPTV propio para Android TV / Chromecast con Google TV.
Se conecta a cualquier servidor tipo **Xtream Codes** (servidor + usuario + contraseña).

> Tus datos de acceso **no** están en este código: se escriben dentro de la app,
> en la pantalla **Ajustes**, y se guardan solo en tu tele.

## Versión 1: TV en vivo
- Categorías a la izquierda, canales a la derecha, con logos.
- **★ Favoritos**: mantén presionado OK sobre un canal para agregarlo o quitarlo.
- **Ocultar categorías**: mantén presionado OK sobre una categoría. Se recuperan en Ajustes.
- Recuerda la última categoría que viste.
- Reproductor a pantalla completa: ▲ ▼ cambian de canal, OK muestra la info del canal.
- Si un canal no abre en `.ts`, prueba solo en formato HLS (`.m3u8`); si se corta, reintenta.
- En Ajustes puedes pegar el enlace de tu proveedor (`get.php?...` o `/playlist/usuario/contraseña/...`)
  y la app extrae servidor, usuario y contraseña.

## Instalar en el Chromecast
1. En el Chromecast: **Ajustes → Sistema → Acerca de** y presiona 7 veces **Compilación de SO de Android TV**
   para activar las opciones de desarrollador.
2. Instala la app **Downloader** (de AFTVnews) desde la Play Store.
3. **Ajustes → Apps → Seguridad y restricciones → Fuentes desconocidas** y activa **Downloader**.
4. Abre Downloader y escribe la dirección del APK de la última versión
   (pestaña **Releases** de este repositorio, archivo `MiTV.apk`).
5. Instala y abre **Mi TV**. Entra a **Ajustes**, escribe tus datos y toca **Probar y guardar**.

Cada versión nueva se instala encima de la anterior sin borrar tus favoritos.

## Compilación
GitHub Actions compila el APK en cada `push` (`.github/workflows/build.yml`) y lo publica en **Releases**.
Para compilar en tu computadora: `./gradlew assembleDebug` (necesita Android SDK y Java 17).

## Próximas versiones
- v2: películas y series.
- v3: guía de programación (EPG).
