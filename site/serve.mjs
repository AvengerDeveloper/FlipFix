// Serves dist/ on http://localhost:8642 for testing. WebUSB works on localhost without HTTPS.
import { createReadStream, statSync } from "node:fs";
import { createServer } from "node:http";
import { extname, join, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("./dist/", import.meta.url));
const types = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript",
  ".css": "text/css",
  ".svg": "image/svg+xml",
  ".json": "application/json",
  ".apk": "application/vnd.android.package-archive",
};
const port = Number(process.env.PORT) || 8642;

createServer((req, res) => {
  const path = decodeURIComponent(new URL(req.url, "http://localhost").pathname);
  const file = resolve(join(root, path.endsWith("/") ? `${path}index.html` : path));
  if (!file.startsWith(resolve(root) + sep)) return res.writeHead(403).end();
  try {
    const { size } = statSync(file);
    res.writeHead(200, { "Content-Type": types[extname(file)] ?? "application/octet-stream", "Content-Length": size });
    createReadStream(file).pipe(res);
  } catch {
    res.writeHead(404).end("Not found");
  }
}).listen(port, "127.0.0.1", () => console.log(`http://localhost:${port}/`));
