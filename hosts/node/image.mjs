import { readFileSync, writeFileSync } from "node:fs";
const [wasmPath, outDir] = process.argv.slice(2);
const { instance: { exports: e } } = await WebAssembly.instantiate(readFileSync(wasmPath),
  { host: { printInt: () => {}, putChar: () => {} } });

// 1. 自前数学関数の精度 (JS の Math と比較)
const maxErr = (f, g, xs) => Math.max(...xs.map((x) => Math.abs(f(x) - g(x)) / Math.max(1, Math.abs(g(x)))));
const range = (a, b, n) => Array.from({ length: n }, (_, i) => a + (b - a) * i / (n - 1));
console.log("max rel err cos  :", maxErr(e.mx_cos, Math.cos, range(-100, 100, 20001)).toExponential(2));
console.log("max rel err sin  :", maxErr(e.mx_sin, Math.sin, range(-100, 100, 20001)).toExponential(2));
console.log("max rel err exp  :", maxErr(e.mx_exp, Math.exp, range(-700, 700, 20001)).toExponential(2));
console.log("max rel err log  :", maxErr(e.mx_log, Math.log, [...range(1e-300, 1e300, 20001), ...range(1e-6, 10, 20001), 5e-324, 1e-310]).toExponential(2));
console.log("max rel err pow  :", maxErr((x) => e.mx_pow(x, 2.2), (x) => Math.pow(x, 2.2), range(0.001, 100, 20001)).toExponential(2));

// 2. 画像生成
const save = (name, len) => {
  const buf = Buffer.alloc(Math.ceil(len / 4) * 4);
  for (let i = 0; i < buf.length / 4; i++) buf.writeInt32LE(e.pngWord(i), i * 4);
  writeFileSync(`${outDir}/${name}`, buf.subarray(0, len));
  console.log(`  -> ${name} (${(len / 1024).toFixed(1)} KiB)`);
};
const time = (label, f) => { const t0 = performance.now(); const r = f(); console.log(`${label}: ${(performance.now() - t0).toFixed(0)} ms`); return r; };

save("raytrace.png", time("rayTrace 640x400 spp=16", () => e.rayTrace(640, 400, 16, 42)));
save("julia.png", time("julia 640x400", () => e.julia(640, 400, -0.8, 0.156)));
