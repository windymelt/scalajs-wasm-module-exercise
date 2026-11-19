import { readFileSync, writeFileSync } from "node:fs";
const [wasmPath, outDir] = process.argv.slice(2);
const { instance: { exports: e } } = await WebAssembly.instantiate(readFileSync(wasmPath), {});
const NX = 256, NY = 160, STEPS = 300;
e.fluidInit(NX, NY);
// 左下と右下から斜め上へ噴流。色は時間で回す
const jets = (t) => {
  const a = t * 0.02;
  e.fluidSplat(30, NY - 20, 2.2, -2.6, 0.9, 0.25 + 0.2 * Math.sin(a), 0.1, 7);
  e.fluidSplat(NX - 30, NY - 20, -2.2, -2.6, 0.1, 0.35, 0.95 + 0.0 * a, 7);
};
const snaps = new Set([300, 600, 1200]);
let total = 0;
for (let t = 1; t <= STEPS; t++) {
  jets(t);
  const t0 = performance.now();
  e.fluidStep(1.0, 20, 0.35, 0.995, 0.998);
  total += performance.now() - t0;
  if (snaps.has(t)) {
    e.fluidRender(0x080a10);
    const buf = Buffer.alloc(NX * NY * 3);
    for (let i = 0; i < NX * NY; i++) { const v = e.fluidPx(i); buf[i * 3] = v >> 16 & 255; buf[i * 3 + 1] = v >> 8 & 255; buf[i * 3 + 2] = v & 255; }
    writeFileSync(`${outDir}/fluid-${t}.ppm`, Buffer.concat([Buffer.from(`P6 ${NX} ${NY} 255\n`), buf]));
    console.log(`step ${t}: energy=${e.fluidEnergy().toFixed(1)}`);
  }
}
console.log(`node: ${(total / STEPS).toFixed(2)} ms/step (${NX}x${NY}, 20 iters)`);
