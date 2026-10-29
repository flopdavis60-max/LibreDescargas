# LibreDescargas

Navegador sin publicidad ni rastreadores + descargador de videos de YouTube, Instagram, Facebook, TikTok y más de 1.000 sitios (usa yt-dlp).

## Qué hace
- Navegador con bloqueo de anuncios y rastreadores (lista básica incluida; menú ⋮ → *Actualizar lista anti-anuncios* para una lista de más de 100.000 dominios).
- Botón **Descargar** en cualquier video: mejor calidad, 720p, 480p o solo audio.
- Aparece en el menú **Compartir** de YouTube, Instagram, TikTok y Facebook.
- Descarga en segundo plano con progreso en notificaciones.
- Guarda en **Descargas/LibreDescargas**.
- Sin analíticas, sin anuncios, sin servidores propios. El motor se actualiza solo una vez al día desde GitHub.
- Si inicias sesión en un sitio dentro de la app, esa sesión se usa para descargar (útil en Instagram y Facebook).

## Cómo obtener la APK (gratis, sin instalar nada)
1. Crea una cuenta en https://github.com (si no tienes).
2. Crea un repositorio nuevo (botón **+** → *New repository*), ponle un nombre y créalo.
3. En el repositorio, pulsa **uploading an existing file** y arrastra **todo el contenido** de esta carpeta (incluida la carpeta `.github`). Pulsa **Commit changes**.
4. Ve a la pestaña **Actions**: verás "Compilar APK" trabajando (tarda unos 5–8 minutos).
5. Cuando termine con ✅, entra a **Releases** (columna derecha de la portada del repositorio) y descarga **app-arm64-v8a-release.apk** desde el celular.
6. Abre el archivo e instálalo (Android pedirá permitir "instalar apps desconocidas").

> En Mac, la carpeta `.github` está oculta. Pulsa Cmd+Shift+. en el Finder para verla, o créala en GitHub con *Add file → Create new file*, nombre `.github/workflows/build.yml`, pegando el contenido de ese archivo.

## Si algo falla
- Una descarga falla: menú ⋮ → **Actualizar motor de descargas** y reintenta.
- YouTube pide "confirmar que no eres un bot" o Instagram pide sesión: inicia sesión en ese sitio dentro de la app y reintenta.
- Los anuncios dentro de los videos de YouTube no siempre se pueden bloquear por completo.

Descarga solo contenido que tengas derecho a guardar.
