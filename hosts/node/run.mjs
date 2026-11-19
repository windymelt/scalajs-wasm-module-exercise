import { readFileSync } from "node:fs";
const bytes = readFileSync(process.argv[2]);
const { instance } = await WebAssembly.instantiate(bytes, {
  host: { printInt: (x) => console.log("printInt:", x), putChar: () => {} },
});
const e = instance.exports;
console.log("fib(30) =", e.fib(30));
e.countdown(3);
