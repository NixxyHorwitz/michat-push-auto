import { neon } from "@neondatabase/serverless";

if (!process.env.DATABASE_URL) {
  throw new Error("DATABASE_URL belum diset");
}

export const sql = neon(process.env.DATABASE_URL);

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
