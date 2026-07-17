# Instalación paso a paso en un Samsung Galaxy S25 Ultra

## Ruta 1 — Android Studio en un ordenador

1. Instala Android Studio y abre la carpeta raíz `CineVault48`.
2. Si Android Studio lo pide, instala Android SDK Platform 36 y Build Tools 36.0.0.
3. Espera a que termine **Gradle Sync**. El proyecto usa JDK 17, AGP 8.13.2 y Gradle 8.13.
4. En el S25 Ultra abre **Ajustes → Acerca del teléfono → Información de software** y pulsa siete veces **Número de compilación**.
5. Vuelve a **Ajustes → Opciones de desarrollador** y activa **Depuración USB**.
6. Conecta el S25 al ordenador por USB y acepta la huella RSA que aparece en el teléfono.
7. En Android Studio selecciona el S25 y pulsa **Run**. Esto instala la variante debug directamente.
8. Para crear un APK, usa **Build → Build APK(s)**. El archivo queda en `app/build/outputs/apk/debug/app-debug.apk`.

## Ruta 2 — compilar gratis con GitHub Actions

El proyecto contiene `.github/workflows/build-apk.yml` y no necesita que el ordenador tenga Android Studio.

1. Crea un repositorio vacío en GitHub.
2. Sube todo el contenido de `CineVault48` conservando la carpeta `.github`.
3. Abre la pestaña **Actions → Build Android APK → Run workflow**.
4. Al finalizar, abre la ejecución y descarga el artefacto `CineVault48-debug-apk`.
5. Descomprime `app-debug.apk` y envíalo al S25 Ultra con Quick Share, Drive o cable.
6. En el teléfono abre el APK. Android pedirá permitir la instalación desde esa app de archivos o navegador; autorízala solo para esta instalación.

## Primera puesta en marcha

1. Abre **CineVault 48** y permite las notificaciones.
2. Pulsa **Añadir**, escribe proyecto/tipo/notas y selecciona imágenes con el Photo Picker.
3. Haz una pulsación larga sobre un thumbnail para iniciar selección múltiple.
4. Usa **Hacer permanentes** para protegerlas.
5. En **Ajustes**, activa primero **Modo simulación** durante una prueba de 2–3 días.
6. En **Ajustes → Carpeta de copia**, elige una carpeta de Google Drive, Dropbox o almacenamiento local si quieres copia externa de permanentes.
7. Para minimizar retrasos de One UI, puedes abrir **Ajustes → Aplicaciones → CineVault 48 → Batería → Sin restricciones**. No es necesario mantener la app abierta.

## Configuración

Edita `app/src/main/assets/config.json` antes de compilar:

```json
{
  "root_folder": "CineVault48",
  "retention_hours": 48,
  "warning_hours_before_expiry": 24,
  "trash_grace_hours": 24,
  "check_interval_hours": 1,
  "thumbnail_size_px": 720,
  "max_import_batch": 30,
  "notifications_enabled": true,
  "dry_run": false
}
```

El interruptor de simulación y el de notificaciones de la app prevalecen sobre sus valores iniciales del JSON.

## Pruebas

Con un S25 o emulador conectado:

```bash
gradle :app:testDebugUnitTest
gradle :app:connectedDebugAndroidTest
```

La suite cubre:

- cálculo de todos los plazos;
- importación, thumbnail y log;
- paso a permanente;
- detección de expiradas;
- papelera y borrado definitivo;
- restauración con 48 h nuevas;
- modo simulación;
- búsqueda por proyecto, fecha y tipo;
- candidatos de notificación;
- 10 imágenes generadas con timestamps simulados.

## Limitaciones intencionadas

- El mantenimiento periódico de Android no es un reloj exacto. Puede ejecutarse algo después de cada hora, nunca antes del plazo guardado.
- Los datos locales de la app se eliminan al desinstalarla. Las copias de la carpeta externa vinculada no.
- El APK debug es adecuado para uso personal. Para distribución, crea una clave de firma y una variante release firmada.
- La v1 no incluye cuentas, backend ni sincronización entre varios usuarios.
