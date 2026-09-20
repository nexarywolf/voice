# Build Android — GitHub Actions

Este repositorio incluye un workflow de GitHub Actions que compila la app
Android **Contador por Voz** y publica los APK / AAB como artefactos
descargables desde la pestaña *Actions* del repositorio.

## Archivos añadidos

| Archivo | Descripción |
|---|---|
| `.github/workflows/build-android.yml` | Workflow principal de build. |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` | Wrapper de Gradle 9.3.1 (necesario para que CI pueda ejecutar `./gradlew`). |
| `README_BUILD.md` | Este archivo. |

## ¿Qué hace el workflow?

1. Se ejecuta automáticamente en `push` / `pull_request` a `main` o `master`,
   y también se puede disparar manualmente desde la pestaña **Actions**
   (`workflow_dispatch`).
2. Configura **JDK 21** (AGP 9.1.1 requiere JDK 21 para ejecutar Gradle) y
   instala los componentes del Android SDK que faltan en el runner
   (`platforms;android-36` y `build-tools;36.0.0`) usando `sdkmanager`
   directamente. Cachea las dependencias de Gradle.
3. **Siempre** compila el APK de **Debug** (`./gradlew assembleDebug`) y lo
   sube como artefacto `apk-debug`.
4. **Solo si** has configurado los secrets de firmado (ver abajo), compila
   además el APK de **Release** firmado y el **AAB** de Release, y los
   sube como `apk-release` y `aab-release`.

## Configuración de firmado (opcional, solo para Release)

Para que el workflow genere el APK/AAB de release firmado, añade estos
**Repository Secrets** en `Settings → Secrets and variables → Actions`:

| Secret | Descripción |
|---|---|
| `SIGNING_KEYSTORE`  | Tu keystore `.jks` codificado en **base64** (ver comando abajo). |
| `KEYSTORE_PASSWORD` | Contraseña del keystore (`STORE_PASSWORD`). |
| `KEY_PASSWORD`       | Contraseña de la clave dentro del keystore. |

> **Importante**: `app/build.gradle.kts` usa el alias **`upload`** hardcoded
> para la clave de release. Tu keystore debe contener una clave con ese
> alias. Si necesitas otro alias, edita `keyAlias = "upload"` en
> `app/build.gradle.kts`.

### Generar el keystore (si no tienes uno)

```bash
keytool -genkey -v -keystore my-upload-key.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias upload
```

### Codificar el keystore en base64

```bash
# macOS (copia al portapapeles)
base64 -i my-upload-key.jks | pbcopy

# Linux (genera un archivo de texto)
base64 -w 0 my-upload-key.jks > keystore.b64
```

Pega el contenido como valor del secret `SIGNING_KEYSTORE`.

## Descargar los artefactos

Después de que el workflow termine:

1. Ve a la pestaña **Actions** del repo en GitHub.
2. Haz clic en la run que quieras.
3. Al final de la página, en **Artifacts**, encontrarás:
   - `apk-debug`  → APK de debug (siempre).
   - `apk-release` → APK de release firmado (si configuraste los secrets).
   - `aab-release` → AAB de release firmado (si configuraste los secrets).

## Notas técnicas

- **AGP 9.1.1** requiere **JDK 21** para ejecutar Gradle. El workflow usa
  `actions/setup-java@v4` con `java-version: '21'`. Aunque la app compile
  con `sourceCompatibility = VERSION_11`, Gradle necesita JDK 21 para correr
  (es un requisito del AGP 9.x, no de la app).
- El SDK del Android en el runner `ubuntu-latest` ya tiene `cmdline-tools`
  preinstalado, pero el action `android-actions/setup-android@v3` tiene un
  bug con el parámetro `packages` multi-línea (pasa todo el bloque como un
  solo nombre de paquete con newlines incluidos). Por eso el workflow
  ejecuta `sdkmanager` directamente con cada paquete como argumento
  separado.
- El plugin `google-services` está configurado con
  `MissingGoogleServicesStrategy.WARN`, así que el build funciona **sin**
  `google-services.json`. Si quieres usar Firebase (Analytics, etc.), añade
  tu `app/google-services.json` al repo.
- El plugin `secrets-gradle-plugin` lee `.env`. El workflow copia
  `.env.example` → `.env` automáticamente. Para usar Gemini AI en el APK,
  descomenta `GEMINI_API_KEY=...` en `.env` y guárdalo como Repository
  Secret (o déjalo comentado: el APK se compila igual).

## Solución de problemas

### `Keystore file '.../debug.keystore' not found for signing config 'debugConfig'`

`app/build.gradle.kts` firma el APK de debug con un `debugConfig` custom que
apunta a `${rootDir}/debug.keystore`. Ese archivo está en `.gitignore`
(así que no se commitea) y por tanto no existe en CI.

El workflow lo genera automáticamente en el step **"Asegurar debug.keystore"**
primero intentando copiarlo de `~/.android/debug.keystore` (el keystore de
debug estándar del SDK) y, si no existe, generándolo con `keytool` (mismas
credenciales: `storepass=android`, `alias=androiddebugkey`,
`keypass=android`, RSA 2048, validez 10000 días).

Si desarrollas localmente y no tienes el archivo, puedes generarlo con:

```bash
keytool -genkey -v -keystore debug.keystore \
  -storepass android -alias androiddebugkey -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```

### `Failed to find package '...'` en el paso "Configurar Android SDK"

Este error ya está resuelto en la versión actual del workflow (se evitaba
usando `android-actions/setup-android@v3` con `packages` multi-línea). Si
vuelve a aparecer, prueba con un solo paquete por llamada en el paso
`Configurar Android SDK`:

```bash
"$SDKMANAGER" --install "platforms;android-36"
"$SDKMANAGER" --install "build-tools;36.0.0"
```

### `Unsupported class file major version 65` o `JDK 21 required`

Confirma que el paso `Configurar JDK 21` esté presente y sin errores. AGP
9.x no arranca con JDK 17.
