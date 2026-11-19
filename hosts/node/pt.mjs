import { readFileSync, writeFileSync } from "node:fs";
const [wasmPath, outDir] = process.argv.slice(2);
const { instance: { exports: e } } = await WebAssembly.instantiate(readFileSync(wasmPath), {});
const W = 640, H = 400;
const save = (name) => {
  const buf = Buffer.alloc(W * H * 3);
  for (let i = 0; i < W * H; i++) { const v = e.ptPx(i); buf[i * 3] = v >> 16 & 255; buf[i * 3 + 1] = v >> 8 & 255; buf[i * 3 + 2] = v & 255; }
  writeFileSync(`${outDir}/${name}.ppm`, Buffer.concat([Buffer.from(`P6 ${W} ${H} 255\n`), buf]));
};
// [名前, 跳ね返りの上限, NEE, サンプル数, ガラスの色, 右に置く物体 (0 ガラス玉, 1 空のグラス, 2 ビール)]
const runs = [
  ["nee-16", 8, 1, 16, 0, 0], ["nonee-16", 8, 0, 16, 0, 0], ["nee-1bounce-16", 1, 1, 16, 0, 0], ["nee-256", 8, 1, 256, 0, 0],
  ["amber-256", 8, 1, 256, 1, 0], ["beer-glass-512", 10, 1, 512, 0, 2], ["empty-glass-256", 10, 1, 256, 0, 1],
];
const only = process.argv[4];
for (const [label, depth, nee, spp, tint, obj] of runs.filter((r) => !only || r[0] === only)) {
  e.ptSetup(W, H, 7, depth, nee, tint, 1.0, obj);
  const t0 = performance.now();
  for (let s = 0; s < spp; s++) for (let y = 0; y < H; y++) e.ptRenderRow(y);
  const ms = performance.now() - t0;
  let sum = 0; for (let i = 0; i < W * H; i += 97) { const v = e.ptPx(i); sum += (v >> 16 & 255) + (v >> 8 & 255) + (v & 255); }
  console.log(`${label}: ${(ms / spp).toFixed(0)} ms/spp, ${(e.ptRays() / ms / 1000).toFixed(1)} Mrays/s, mean=${(sum / (3 * Math.ceil(W * H / 97))).toFixed(1)}`);
  save(label);
}
