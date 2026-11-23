// Membuat pasangan kunci tanda tangan lisensi (ECDSA P-256) + password admin.
// Private key HANYA disimpan di server (.env.local / Environment Variables Vercel).
// Public key ditanam di aplikasi Android untuk memverifikasi token.
//
// Pakai: npm run keys:generate   (tidak menimpa nilai yang sudah ada)
import { generateKeyPairSync, randomBytes } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";

const envPath = new URL("../.env.local", import.meta.url);
let env = existsSync(envPath) ? readFileSync(envPath, "utf8") : "";
const has = (k) => new RegExp(`^${k}=`, "m").test(env);
const add = (k, v) => {
  if (!has(k)) env += (env.endsWith("\n") || env === "" ? "" : "\n") + `${k}=${v}\n`;
};

if (!has("LICENSE_PRIVATE_KEY")) {
  const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  add("LICENSE_PRIVATE_KEY", privateKey.export({ type: "pkcs8", format: "der" }).toString("base64"));
  add("LICENSE_PUBLIC_KEY", publicKey.export({ type: "spki", format: "der" }).toString("base64"));
}
add("ADMIN_PASSWORD", randomBytes(9).toString("base64url"));
add("SESSION_SECRET", randomBytes(32).toString("base64url"));

writeFileSync(envPath, env);
const pub = env.match(/^LICENSE_PUBLIC_KEY=(.*)$/m)[1];
console.log("Kunci siap di .env.local");
console.log("PUBLIC KEY (tanam di app):", pub);
