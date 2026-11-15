# Muga TV

Reproductor IPTV propio para Android TV / Chromecast con Google TV.
Se conecta a cualquier servidor tipo **Xtream Codes** (servidor + usuario + contraseña).

> Tus datos de acceso **no** están en este código: se escriben dentro de la app,
> en la pantalla **Ajustes**, y se guardan solo en tu tele.

## Qué hace (versión 3)
- **Perfiles**: Jenny y Julio (adultos), Cam y Maju (infantiles). PIN opcional para adultos, apagado de inicio.
- **Inicio estilo streaming**: un destacado grande arriba y filas de pósters: Seguir viendo, Mi lista,
  Canales de México, Películas recién agregadas, Series con episodios nuevos y categorías.
- **Pestañas**: Inicio · TV en vivo · Películas · Series · Buscar.
- **Fichas** de película y serie con imagen grande, sinopsis, temporadas y episodios.
- **Seguir viendo** por perfil: recuerda el minuto exacto; las series siguen en el episodio siguiente.
- **Siguiente episodio automático** con cuenta regresiva de 10 segundos (se puede cancelar).
- **Perfiles infantiles**: solo ven las categorías permitidas. Se detectan solas por nombre
  (Infantil, Kids, Niños, Animación…) y se ajustan en **Ajustes → Elegir categorías infantiles**.
- **TV en vivo**: categorías de México primero; ▲ ▼ cambian de canal; mantener OK agrega a Mi lista.
- **Secciones por tipo**: Animación, Acción y aventura, Comedia, Terror y suspenso, Ciencia ficción, Romance,
  Documentales y Para toda la familia (por género del título o nombre de su categoría).
- **Avance automático**: al pasar por un póster, arriba se ve su imagen, datos y sinopsis (se apaga en Ajustes).
- **Recomendado para ti** y **Porque viste…**, según lo que ve cada perfil.
- **Guía de programación** en TV en vivo: qué pasa ahora y qué sigue (si el proveedor la tiene).
- **Ajustes → Perfiles**: renombrar, color, infantil/adulto, agregar (hasta 8) y borrar.
- **Ajustes → Ordenar y ocultar categorías** para los perfiles de adultos.
- Diseño propio, azul oscuro y minimalista. No usa logotipos ni diseños de otras plataformas.

## Instalar en el Chromecast
1. En el Chromecast: **Ajustes → Sistema → Acerca de** y presiona 7 veces **Compilación de SO de Android TV**
   para activar las opciones de desarrollador.
2. Instala la app **Downloader** (de AFTVnews) desde la Play Store.
3. **Ajustes → Apps → Seguridad y restricciones → Fuentes desconocidas** y activa **Downloader**.
4. Abre Downloader y escribe la dirección del APK de la última versión
   (pestaña **Releases** de este repositorio, archivo `MiTV.apk`).
5. Instala y abre **Mi TV**. La primera vez abre **Ajustes**: escribe tus datos y toca **Probar y guardar**.

Cada versión nueva se instala encima de la anterior sin borrar tus favoritos.

## Compilación
GitHub Actions compila el APK en cada `push` (`.github/workflows/build.yml`) y lo publica en **Releases**.
Para compilar en tu computadora: `./gradlew assembleDebug` (necesita Android SDK y Java 17).

