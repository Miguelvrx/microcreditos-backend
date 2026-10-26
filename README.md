# Sistema de Gestión de Microcréditos

Sistema para gestionar clientes, créditos, abonos, mora, reportes y auditoría.

> **Proyecto privado y propietario.** Todos los derechos reservados.
> Prohibido copiar, distribuir o usar este código sin autorización escrita
> del titular. Ver [LICENSE](LICENSE).

## Tecnologías

- **Backend:** Java 21, Spring Boot, Spring Security (JWT), Spring Data JPA, Flyway
- **Base de datos:** PostgreSQL 17
- **Frontend:** React + TypeScript (Vite)
- **Reportes:** OpenPDF (PDF) y Apache POI (Excel)

## Estructura

```
microcreditos/
├── backend/    API REST (Spring Boot)
├── frontend/   Interfaz web (React + Vite)
├── LICENSE
└── README.md
```

## Requisitos

Java 21, Node.js 22, PostgreSQL 17 y Maven (se incluye `mvnw`).

## Configuración

1. Crea la base de datos `microcreditos_db` en PostgreSQL.
2. Copia `.env.example` a `.env` y completa tus valores. **Nunca subas el `.env`.**
3. Define las variables de entorno antes de arrancar el backend
   (`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
   `SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET`).

El esquema lo crea Flyway al arrancar. No se modifica a mano: cada cambio va en
un script nuevo en `backend/src/main/resources/db/migration`.

## Ejecutar

Backend (desde `backend/`):

```powershell
.\mvnw.cmd spring-boot:run
```

Frontend (desde `frontend/`):

```powershell
npm install
npm run dev
```

La interfaz queda en http://localhost:5173.

## Pruebas

```powershell
cd backend
.\mvnw.cmd test
```

## Seguridad

- No subir secretos, contraseñas ni respaldos de la base (`*.dump`).
- Las contraseñas de usuarios se guardan con BCrypt.
- Los endpoints administrativos requieren rol `ADMIN`.

## Licencia

Software propietario. Todos los derechos reservados. Ver [LICENSE](LICENSE).