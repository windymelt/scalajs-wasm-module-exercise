import { readFileSync } from "node:fs";
let buf = [];
const { instance } = await WebAssembly.instantiate(readFileSync(process.argv[2]), {
  host: { printInt: (x) => console.log("printInt:", x), putChar: (c) => { if (c === 10) { console.log(String.fromCharCode(...buf)); buf = []; } else buf.push(c); } },
});
const e = instance.exports;
console.log("=== [Node.js] mandelbrot ===");
e.mandelbrot(72, 24, 64);
console.log("=== [Node.js] calc ===");
e.calcDemo();
console.log("=== [Node.js] text ===");
const enc = new TextEncoder().encode(process.argv[3] ?? "寿司とビールと寿司とScala.js 🍣🍺");
e.inClear();
enc.forEach((b) => e.inPush(b));
const n = e.decorateBuffered();
console.log(new TextDecoder().decode(Uint8Array.from({ length: n }, (_, i) => e.outAt(i))));
