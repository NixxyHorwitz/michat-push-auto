import { NextResponse } from "next/server";
import { validateLicense } from "@/lib/license";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** POST { key, deviceId, appVersion? } → cek berkala; balas token baru atau status terkunci */
export async function POST(req: Request) {
  try {
    const body = await req.json().catch(() => ({}));
    const result = await validateLicense({
      key: body.key,
      deviceId: body.deviceId,
      appVersion: typeof body.appVersion === "string" ? body.appVersion.slice(0, 20) : undefined,
      allowNewDevice: false,
    });
    return NextResponse.json(result, { status: result.ok ? 200 : 403 });
  } catch (e) {
    console.error("check error", e);
    return NextResponse.json({ ok: false, code: "server_error", message: "Server error", serverTime: Date.now() }, { status: 500 });
  }
}
