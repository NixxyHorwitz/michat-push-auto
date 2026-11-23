import { NextResponse } from "next/server";
import { sql } from "@/lib/db";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** Cek konfigurasi server. Hanya melaporkan ada/tidaknya variabel, TIDAK menampilkan nilainya. */
export async function GET() {
  const env = {
    DATABASE_URL: !!process.env.DATABASE_URL,
    LICENSE_PRIVATE_KEY: !!process.env.LICENSE_PRIVATE_KEY,
    LICENSE_PUBLIC_KEY: !!process.env.LICENSE_PUBLIC_KEY,
    ADMIN_PASSWORD: !!process.env.ADMIN_PASSWORD,
    SESSION_SECRET: !!process.env.SESSION_SECRET,
  };
  let database = "belum dicek";
  if (env.DATABASE_URL) {
    try {
      const rows = (await sql`SELECT count(*)::int AS n FROM plans`) as { n: number }[];
      database = `ok (${rows[0].n} paket)`;
    } catch (e) {
      database = `gagal: ${(e as Error).message.slice(0, 120)}`;
    }
  }
  const ok = Object.values(env).every(Boolean) && database.startsWith("ok");
  return NextResponse.json({ ok, env, database }, { status: ok ? 200 : 500 });
}
