// Serveur de signalements DJStef GPS (Cloudflare Worker + KV "REPORTS").
// Routes : GET /reports?lat&lon&r   POST /report   POST /confirm
const TTL = 4 * 3600; // un signalement expire au bout de 4 h
const JSON_H = {
  "content-type": "application/json",
  "access-control-allow-origin": "*",
};
const TYPES = ["Radar fixe", "Radar mobile", "Radar feu rouge", "Vitesse moyenne", "Contrôle", "Accident", "Danger"];

function dist(aLat, aLon, bLat, bLon) {
  const R = 6371000, rad = Math.PI / 180;
  const dLat = (bLat - aLat) * rad, dLon = (bLon - aLon) * rad;
  const x = Math.sin(dLat / 2) ** 2 + Math.cos(aLat * rad) * Math.cos(bLat * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.atan2(Math.sqrt(x), Math.sqrt(1 - x));
}
const out = (obj, status = 200) => new Response(JSON.stringify(obj), { status, headers: JSON_H });

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === "OPTIONS") return out({});

    if (req.method === "GET" && url.pathname === "/reports") {
      const lat = parseFloat(url.searchParams.get("lat"));
      const lon = parseFloat(url.searchParams.get("lon"));
      const r = parseFloat(url.searchParams.get("r") || "30000");
      if (isNaN(lat) || isNaN(lon)) return out({ error: "lat/lon" }, 400);
      const list = await env.REPORTS.list({ prefix: "r:" });
      const reports = [];
      for (const k of list.keys) {
        const v = await env.REPORTS.get(k.name, "json");
        if (v && dist(lat, lon, v.lat, v.lon) <= r) reports.push(v);
      }
      return out({ reports });
    }

    if (req.method === "POST" && url.pathname === "/report") {
      const b = await req.json().catch(() => null);
      if (!b || !TYPES.includes(b.type) || typeof b.lat !== "number" || typeof b.lon !== "number")
        return out({ error: "invalide" }, 400);
      // Même type à moins de 150 m : on confirme l'existant au lieu d'en créer un autre
      const list = await env.REPORTS.list({ prefix: "r:" });
      for (const k of list.keys) {
        const v = await env.REPORTS.get(k.name, "json");
        if (v && v.type === b.type && dist(v.lat, v.lon, b.lat, b.lon) < 150) {
          v.conf = (v.conf || 1) + 1;
          v.ts = Date.now();
          await env.REPORTS.put(k.name, JSON.stringify(v), { expirationTtl: TTL });
          return out({ id: v.id, merged: true });
        }
      }
      const id = crypto.randomUUID().slice(0, 8);
      const rep = { id, type: b.type, lat: b.lat, lon: b.lon, ts: Date.now(), conf: 1 };
      await env.REPORTS.put("r:" + id, JSON.stringify(rep), { expirationTtl: TTL });
      return out({ id });
    }

    if (req.method === "POST" && url.pathname === "/confirm") {
      const b = await req.json().catch(() => null);
      const v = b && (await env.REPORTS.get("r:" + b.id, "json"));
      if (!v) return out({ error: "inconnu" }, 404);
      v.conf = (v.conf || 1) + 1;
      v.ts = Date.now();
      await env.REPORTS.put("r:" + v.id, JSON.stringify(v), { expirationTtl: TTL });
      return out({ ok: true });
    }

    return out({ name: "DJStef GPS reports" });
  },
};
