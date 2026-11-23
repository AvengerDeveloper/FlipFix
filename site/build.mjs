// Builds the page into dist/: bundles src/setup.js with the Tango ADB libraries and copies the static files.
// The Pages workflow then adds FlipFix.apk and release.json from the latest release.
import { build } from "esbuild";
import { cp, mkdir, rm } from "node:fs/promises";

await rm("dist", { recursive: true, force: true });
await mkdir("dist");
await build({
  entryPoints: ["src/setup.js"],
  outfile: "dist/setup.js",
  bundle: true,
  format: "esm",
  minify: true,
  target: ["chrome110", "edge110"],
  legalComments: "eof",
  logLevel: "warning",
});
for (const file of ["index.html", "style.css", "icon.svg", "demo.mp4", "demo-poster.jpg", "og.png"]) await cp(`src/${file}`, `dist/${file}`);
console.log("built dist/");
