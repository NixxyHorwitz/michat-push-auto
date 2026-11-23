import { createHmac, timingSafeEqual } from "node:crypto";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";

const COOKIE = "mca_admin";
const MAX_AGE_S = 7 * 24 * 60 * 60;

function secret() {
  const s = process.env.SESSION_SECRET;
  if (!s) throw new Error("SESSION_SECRET belum diset");
  return s;
}

function sign(value: string) {
  return createHmac("sha256", secret()).update(value).digest("base64url");
}

export function checkPassword(input: string): boolean {
  const expected = process.env.ADMIN_PASSWORD ?? "";
  const a = Buffer.from(input);
  const b = Buffer.from(expected);
  return expected.length > 0 && a.length === b.length && timingSafeEqual(a, b);
}

export async function createSession() {
  const issued = Date.now().toString();
  (await cookies()).set(COOKIE, `${issued}.${sign(issued)}`, {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    path: "/",
    maxAge: MAX_AGE_S,
  });
}

export async function destroySession() {
  (await cookies()).delete(COOKIE);
}

export async function isAdmin(): Promise<boolean> {
  const raw = (await cookies()).get(COOKIE)?.value;
  if (!raw) return false;
  const [issued, mac] = raw.split(".");
  if (!issued || !mac) return false;
  const good = Buffer.from(sign(issued));
  const got = Buffer.from(mac);
  if (good.length !== got.length || !timingSafeEqual(good, got)) return false;
  return Date.now() - Number(issued) < MAX_AGE_S * 1000;
}

export async function requireAdmin() {
  if (!(await isAdmin())) redirect("/admin/login");
}
