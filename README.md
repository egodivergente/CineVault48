# CineVault 48

Galería Android temporal por proyectos, con Photo Picker, retención y modo de simulación. Prototipo con código público.

Prototipo de galería temporal para material creativo, con importación mediante Photo Picker, proyectos, papelera y simulación de las operaciones de retención.

**Estado:** código público con documentación y pruebas incluidas. Esta auditoría no ha compilado el proyecto ni probado una instalación actual. No hay una Release publicada en la revisión del 6 de octubre de 2026.

| Lectura | Documento |
| --- | --- |
| Diseño y módulos | [Arquitectura](docs/ARCHITECTURE.md) |
| Modelo de datos | [Esquema SQLite](docs/DATABASE.sql) |
| Procedimiento histórico | [Instalación](docs/DEPLOYMENT.md) |

La raíz conserva el proyecto Android y una copia `CineVault48-source/`. No se ha demostrado que esas dos carpetas sean equivalentes. El workflow de compilación existe; su presencia no acredita una ejecución exitosa o un APK actual.

El diseño contempla retención y borrado de copias temporales. Revisar la configuración y el modo de simulación antes de usar material real. Las copias a proveedores externos se realizan mediante carpetas elegidas por el usuario; no se presenta una API de Drive o Dropbox verificada.

[Otros productos de Egodivergente](https://github.com/vidaltb94-collab/vidaltb94-collab/blob/main/PROJECTS.md).

## Estado y continuidad

Los archivos de código y las pruebas existentes se conservan. Se ha revisado la descripción documental; no se presenta este prototipo como la mejor aplicación del ecosistema.
