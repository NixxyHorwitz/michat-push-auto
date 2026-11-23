// Membuat tabel lisensi + paket default di Neon. Aman dijalankan berulang.
// Pakai: npm run db:setup
import { neon } from "@neondatabase/serverless";

const sql = neon(process.env.DATABASE_URL);

await sql`
  CREATE TABLE IF NOT EXISTS plans (
    id          TEXT PRIMARY KEY,
    name        TEXT NOT NULL,
    features    JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
  )`;

await sql`
  CREATE TABLE IF NOT EXISTS license_keys (
    key           TEXT PRIMARY KEY,
    plan_id       TEXT NOT NULL REFERENCES plans(id),
    duration_days INTEGER,               -- NULL = permanen
    max_devices   INTEGER NOT NULL DEFAULT 1,
    status        TEXT NOT NULL DEFAULT 'active',  -- active | revoked
    note          TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at  TIMESTAMPTZ,           -- masa aktif dihitung sejak aktivasi pertama
    expires_at    TIMESTAMPTZ
  )`;

await sql`
  CREATE TABLE IF NOT EXISTS activations (
    key          TEXT NOT NULL REFERENCES license_keys(key) ON DELETE CASCADE,
    device_id    TEXT NOT NULL,
    device_name  TEXT,
    app_version  TEXT,
    first_seen   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (key, device_id)
  )`;

// Paket default (bisa diubah dari panel admin)
const plans = [
  ["trial", "Trial", { custom_message: true, gender_icon: true, max_target: 10 }],
  ["basic", "Basic", { custom_message: false, gender_icon: false, max_target: 50 }],
  ["pro", "Pro", { custom_message: true, gender_icon: true, max_target: 200 }],
];
for (const [id, name, features] of plans) {
  await sql`
    INSERT INTO plans (id, name, features) VALUES (${id}, ${name}, ${JSON.stringify(features)}::jsonb)
    ON CONFLICT (id) DO NOTHING`;
}

const rows = await sql`SELECT id, name, features FROM plans ORDER BY created_at`;
console.log("Tabel siap. Paket:", rows.map((r) => `${r.id}=${JSON.stringify(r.features)}`).join("  "));
