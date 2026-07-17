# CineVault 48

Galería Android nativa para pantallazos cinematográficos generados con IA, diseñada para Samsung Galaxy S25 Ultra.

## Resultado

- Importación privada mediante Photo Picker, sin permiso para leer toda la galería.
- Retención temporal de 48 horas.
- Aviso preventivo antes de papelera y aviso final 24 h antes del borrado físico.
- Papelera con 24 horas adicionales de gracia.
- Modo simulación que registra sin mover ni borrar.
- Grid móvil de dos columnas con badges de tiempo restante.
- Pulsación larga o checkbox para selección múltiple.
- Filtros por proyecto, fecha y tipo (`prompt`, `frame`, `reference`).
- SQLite con tablas `imagenes`, `proyectos` y `logs`.
- Copia opcional de permanentes a una carpeta local, Google Drive o Dropbox.
- Trabajo periódico persistente con WorkManager.
- Pruebas unitarias e instrumentadas, incluida una fixture de 10 imágenes.
- Workflow de GitHub Actions que genera un APK instalable.

## Orden de lectura

1. [Arquitectura completa](docs/ARCHITECTURE.md)
2. [Código por módulos](docs/ARCHITECTURE.md#módulos-y-responsabilidades)
3. [Esquema SQLite](docs/DATABASE.sql)
4. [Instalación paso a paso](docs/DEPLOYMENT.md)

## Inicio rápido

Abre este directorio con Android Studio, conecta el S25 Ultra y pulsa **Run**. Para una compilación remota, sube el proyecto a GitHub y ejecuta el workflow **Build Android APK**.

## Configuración predeterminada

Los valores viven en `app/src/main/assets/config.json`. Para probar con seguridad, abre la app y activa **Ajustes → Modo simulación** antes de importar material real.

## Privacidad

La v1 funciona sin cuenta y sin servidor. Las temporales se guardan en el espacio específico de la app; solo las imágenes elegidas explícitamente como permanentes pueden copiarse al proveedor externo que el usuario vincule.
