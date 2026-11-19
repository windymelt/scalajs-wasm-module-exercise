import { readFileSync, writeFileSync } from "node:fs";
const [wasmPath, outDir] = process.argv.slice(2);
const { instance: { exports: e } } = await WebAssembly.instantiate(readFileSync(wasmPath), {});
const W = 640, H = 400;
const save = (name) => {
  const buf = Buffer.alloc(W * H * 3);
  for (let i = 0; i < W * H; i++) { const v = e.ptPx(i); buf[i * 3] = v >> 16 & 255; buf[i * 3 + 1] = v >> 8 & 255; buf[i * 3 + 2] = v & 255; }
  writeFileSync(`${outDir}/${name}.ppm`, Buffer.concat([Buffer.from(`P6 ${W} ${H} 255\n`), buf]));
};
for (const [label, depth, nee, spp] of [["nee-16", 8, 1, 16], ["nonee-16", 8, 0, 16], ["nee-1bounce-16", 1, 1, 16], ["nee-256", 8, 1, 256]]) {
  e.ptSetup(W, H, 7, depth, nee);
  const t0 = performance.now();
  for (let s = 0; s < spp; s++) for (let y = 0; y < H; y++) e.ptRenderRow(y);
  const ms = performance.now() - t0;
  let sum = 0; for (let i = 0; i < W * H; i += 97) { const v = e.ptPx(i); sum += (v >> 16 & 255) + (v >> 8 & 255) + (v & 255); }
  console.log(`${label}: ${(ms / spp).toFixed(0)} ms/spp, ${(e.ptRays() / ms / 1000).toFixed(1)} Mrays/s, mean=${(sum / (3 * Math.ceil(W * H / 97))).toFixed(1)}`);
  save(label);
}
