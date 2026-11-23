"use server";

import { revalidatePath } from "next/cache";
import { redirect } from "next/navigation";
import { checkPassword, createSession, destroySession, requireAdmin } from "./auth";
import { sql } from "./db";
import { generateKey, normalizeKey } from "./license";

export async function loginAction(_prev: { error?: string } | undefined, form: FormData) {
  // Jeda kecil untuk memperlambat tebak-tebakan password
  await new Promise((r) => setTimeout(r, 400));
  if (!checkPassword(String(form.get("password") ?? ""))) return { error: "Password salah" };
  await createSession();
  redirect("/admin");
}

export async function logoutAction() {
  await destroySession();
  redirect("/admin/login");
}

export type CreateKeysState = { keys?: string[]; error?: string; summary?: string };

export async function createKeysAction(_prev: CreateKeysState | undefined, form: FormData): Promise<CreateKeysState> {
  await requireAdmin();
  const planId = String(form.get("plan") ?? "");
  const durationRaw = String(form.get("duration") ?? "");
  const customDays = Number(form.get("customDays") ?? 0);
  const qty = Math.min(Math.max(Number(form.get("qty") ?? 1), 1), 200);
  const maxDevices = Math.min(Math.max(Number(form.get("maxDevices") ?? 1), 1), 20);
  const note = String(form.get("note") ?? "").slice(0, 200) || null;

  let days: number | null;
  if (durationRaw === "lifetime") days = null;
  else if (durationRaw === "custom") days = Math.round(customDays);
  else days = Number(durationRaw);
  if (days !== null && (!Number.isFinite(days) || days < 1 || days > 3650)) return { error: "Durasi tidak valid" };

  const plan = (await sql`SELECT id, name FROM plans WHERE id = ${planId}`) as any[];
  if (!plan.length) return { error: "Paket tidak ditemukan" };

  const keys: string[] = [];
  while (keys.length < qty) {
    const k = generateKey();
    const inserted = (await sql`
      INSERT INTO license_keys (key, plan_id, duration_days, max_devices, note)
      VALUES (${k}, ${planId}, ${days}, ${maxDevices}, ${note})
      ON CONFLICT (key) DO NOTHING RETURNING key`) as any[];
    if (inserted.length) keys.push(k);
  }
  revalidatePath("/admin");
  return {
    keys,
    summary: `${keys.length} key ${plan[0].name} · ${days === null ? "Permanen" : `${days} hari`} · ${maxDevices} HP`,
  };
}

export async function keyAction(form: FormData) {
  await requireAdmin();
  const key = normalizeKey(form.get("key"));
  const op = String(form.get("op") ?? "");
  switch (op) {
    case "revoke":
      await sql`UPDATE license_keys SET status = 'revoked' WHERE key = ${key}`;
      break;
    case "restore":
      await sql`UPDATE license_keys SET status = 'active' WHERE key = ${key}`;
      break;
    case "reset":
      await sql`DELETE FROM activations WHERE key = ${key}`;
      break;
    case "extend": {
      const days = Math.min(Math.max(Number(form.get("days") ?? 0), 1), 3650);
      // Belum aktif → tambah durasi. Sudah aktif → mundurkan tanggal kadaluarsa (dari sekarang jika sudah lewat).
      await sql`
        UPDATE license_keys SET
          duration_days = CASE WHEN activated_at IS NULL AND duration_days IS NOT NULL THEN duration_days + ${days} ELSE duration_days END,
          expires_at = CASE WHEN activated_at IS NOT NULL AND expires_at IS NOT NULL
                            THEN GREATEST(expires_at, now()) + make_interval(days => ${days}) ELSE expires_at END
        WHERE key = ${key}`;
      break;
    }
    case "delete":
      await sql`DELETE FROM license_keys WHERE key = ${key}`;
      break;
  }
  revalidatePath("/admin");
}

export async function savePlanAction(form: FormData) {
  await requireAdmin();
  const id = String(form.get("id") ?? "").toLowerCase().replace(/[^a-z0-9_-]/g, "").slice(0, 30);
  const name = String(form.get("name") ?? "").slice(0, 40) || id;
  if (!id) return;
  const features = {
    custom_message: form.get("custom_message") === "on",
    gender_icon: form.get("gender_icon") === "on",
    max_target: Math.min(Math.max(Number(form.get("max_target") ?? 20), 1), 1000),
  };
  await sql`
    INSERT INTO plans (id, name, features) VALUES (${id}, ${name}, ${JSON.stringify(features)}::jsonb)
    ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, features = EXCLUDED.features`;
  revalidatePath("/admin");
}
