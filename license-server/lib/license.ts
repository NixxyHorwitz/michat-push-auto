import { createPrivateKey, randomInt, sign } from "node:crypto";
import { sql, type Features } from "./db";

/** Berapa lama token boleh dipakai offline sebelum app wajib cek ulang ke server */
export const OFFLINE_GRACE_MS = 24 * 60 * 60 * 1000;

// Tanpa huruf/angka yang mirip (0/O, 1/I/L) supaya key gampang diketik pembeli
const ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

export function generateKey(): string {
  const block = () => Array.from({ length: 4 }, () => ALPHABET[randomInt(ALPHABET.length)]).join("");
  return `MCA-${block()}-${block()}-${block()}`;
}

export function normalizeKey(raw: unknown): string {
  return String(raw ?? "").toUpperCase().replace(/[^A-Z0-9-]/g, "").slice(0, 40);
}

export type TokenPayload = {
  v: 1;
  k: string; // key
  d: string; // device id
  p: string; // plan id
  pn: string; // nama paket
  f: Features;
  exp: number | null; // kadaluarsa (epoch ms), null = permanen
  iat: number; // waktu server saat token dibuat
  chk: number; // app wajib cek ulang sebelum waktu ini
};

let cachedKey: ReturnType<typeof createPrivateKey> | null = null;
function privateKey() {
  if (!cachedKey) {
    const b64 = process.env.LICENSE_PRIVATE_KEY;
    if (!b64) throw new Error("LICENSE_PRIVATE_KEY belum diset");
    cachedKey = createPrivateKey({ key: Buffer.from(b64, "base64"), format: "der", type: "pkcs8" });
  }
  return cachedKey;
}

/** Token = base64url(JSON) + "." + base64url(tanda tangan ECDSA P-256/SHA-256, format DER) */
export function signToken(payload: TokenPayload): string {
  const body = Buffer.from(JSON.stringify(payload)).toString("base64url");
  const sig = sign("sha256", Buffer.from(body), { key: privateKey(), dsaEncoding: "der" });
  return `${body}.${sig.toString("base64url")}`;
}

export type LicenseResult =
  | { ok: true; token: string; plan: string; expiresAt: number | null; features: Features; serverTime: number }
  | { ok: false; code: "invalid" | "revoked" | "expired" | "device_limit" | "not_activated" | "bad_request"; message: string; serverTime: number };

const fail = (code: Extract<LicenseResult, { ok: false }>["code"], message: string): LicenseResult => ({
  ok: false,
  code,
  message,
  serverTime: Date.now(),
});

/**
 * Validasi key untuk satu device.
 * allowNewDevice=true  → aktivasi (boleh mengikat HP baru bila slot masih ada)
 * allowNewDevice=false → cek berkala (HP harus sudah terikat)
 */
export async function validateLicense(opts: {
  key: string;
  deviceId: string;
  deviceName?: string;
  appVersion?: string;
  allowNewDevice: boolean;
}): Promise<LicenseResult> {
  const key = normalizeKey(opts.key);
  const deviceId = String(opts.deviceId ?? "").slice(0, 128);
  if (!key || !deviceId) return fail("bad_request", "Key dan Device ID wajib diisi");

  const rows = (await sql`
    SELECT k.*, p.name AS plan_name, p.features
    FROM license_keys k JOIN plans p ON p.id = k.plan_id
    WHERE k.key = ${key}`) as any[];
  const lic = rows[0];
  if (!lic) return fail("invalid", "Key tidak ditemukan");
  if (lic.status === "revoked") return fail("revoked", "Key sudah dinonaktifkan");

  const now = new Date();
  if (lic.expires_at && new Date(lic.expires_at) <= now) return fail("expired", "Masa aktif key sudah habis");

  // Ikat device
  const existing = (await sql`SELECT 1 FROM activations WHERE key = ${key} AND device_id = ${deviceId}`) as any[];
  if (existing.length) {
    await sql`
      UPDATE activations SET last_seen = now(), device_name = COALESCE(${opts.deviceName ?? null}, device_name),
             app_version = COALESCE(${opts.appVersion ?? null}, app_version)
      WHERE key = ${key} AND device_id = ${deviceId}`;
  } else {
    if (!opts.allowNewDevice) return fail("not_activated", "HP ini belum diaktivasi dengan key tersebut");
    const [{ n }] = (await sql`SELECT count(*)::int AS n FROM activations WHERE key = ${key}`) as any[];
    if (n >= lic.max_devices) return fail("device_limit", "Key sudah dipakai di HP lain");
    await sql`
      INSERT INTO activations (key, device_id, device_name, app_version)
      VALUES (${key}, ${deviceId}, ${opts.deviceName ?? null}, ${opts.appVersion ?? null})`;
  }

  // Masa aktif mulai dihitung saat aktivasi pertama
  let expiresAt: Date | null = lic.expires_at ? new Date(lic.expires_at) : null;
  if (!lic.activated_at) {
    expiresAt = lic.duration_days ? new Date(now.getTime() + lic.duration_days * 86_400_000) : null;
    await sql`UPDATE license_keys SET activated_at = ${now.toISOString()}, expires_at = ${expiresAt?.toISOString() ?? null}
              WHERE key = ${key} AND activated_at IS NULL`;
  }

  const iat = now.getTime();
  const exp = expiresAt ? expiresAt.getTime() : null;
  const token = signToken({
    v: 1,
    k: key,
    d: deviceId,
    p: lic.plan_id,
    pn: lic.plan_name,
    f: lic.features ?? {},
    exp,
    iat,
    chk: Math.min(iat + OFFLINE_GRACE_MS, exp ?? Number.MAX_SAFE_INTEGER),
  });
  return { ok: true, token, plan: lic.plan_name, expiresAt: exp, features: lic.features ?? {}, serverTime: iat };
}
