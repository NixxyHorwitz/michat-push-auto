import { neon, type NeonQueryFunction } from "@neondatabase/serverless";

// Koneksi dibuat saat query pertama (bukan saat file dimuat), supaya `next build`
// tetap berhasil walaupun DATABASE_URL belum diset di lingkungan build.
let client: NeonQueryFunction<false, false> | null = null;

function db(): NeonQueryFunction<false, false> {
  if (!client) {
    const url = process.env.DATABASE_URL;
    if (!url) throw new Error("DATABASE_URL belum diset (Vercel → Settings → Environment Variables)");
    client = neon(url);
  }
  return client;
}

export const sql = ((strings: TemplateStringsArray, ...values: unknown[]) =>
  db()(strings, ...values)) as unknown as NeonQueryFunction<false, false>;

export type Features = {
  custom_message?: boolean;
  gender_icon?: boolean;
  max_target?: number;
};

export type Plan = { id: string; name: string; features: Features };

export type LicenseRow = {
  key: string;
  plan_id: string;
  plan_name: string;
  features: Features;
  duration_days: number | null;
  max_devices: number;
  status: "active" | "revoked";
  note: string | null;
  created_at: string;
  activated_at: string | null;
  expires_at: string | null;
  device_count: number;
  last_seen: string | null;
};
