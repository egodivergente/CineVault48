PRAGMA foreign_keys = ON;

CREATE TABLE proyectos (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL COLLATE NOCASE UNIQUE,
    created_at INTEGER NOT NULL
);

CREATE TABLE imagenes (
    id TEXT PRIMARY KEY,
    project_id INTEGER NOT NULL,
    original_name TEXT NOT NULL,
    file_name TEXT NOT NULL,
    file_path TEXT NOT NULL,
    thumbnail_path TEXT,
    type TEXT NOT NULL CHECK(type IN ('prompt', 'frame', 'reference')),
    status TEXT NOT NULL CHECK(status IN ('temp', 'permanent', 'trash', 'deleted')),
    notes TEXT NOT NULL DEFAULT '',
    mime_type TEXT NOT NULL,
    width INTEGER NOT NULL DEFAULT 0,
    height INTEGER NOT NULL DEFAULT 0,
    size_bytes INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    expires_at INTEGER,
    warning_at INTEGER,
    notified_at INTEGER,
    trashed_at INTEGER,
    delete_after INTEGER,
    deleted_at INTEGER,
    FOREIGN KEY(project_id) REFERENCES proyectos(id) ON DELETE RESTRICT
);

CREATE TABLE logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    image_id TEXT,
    action TEXT NOT NULL,
    details TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL,
    FOREIGN KEY(image_id) REFERENCES imagenes(id) ON DELETE SET NULL
);

CREATE INDEX idx_images_status_expiry ON imagenes(status, expires_at);
CREATE INDEX idx_images_trash_delete ON imagenes(status, delete_after);
CREATE INDEX idx_images_project_date ON imagenes(project_id, created_at);
CREATE INDEX idx_images_type ON imagenes(type);
CREATE INDEX idx_logs_image_date ON logs(image_id, created_at);
