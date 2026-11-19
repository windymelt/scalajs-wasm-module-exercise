import { readFileSync } from "node:fs";
let buf = [];
const { instance: { exports: e } } = await WebAssembly.instantiate(readFileSync(process.argv[2]), {
  host: { putChar: (c) => { if (c === 10) { console.log(String.fromCharCode(...buf)); buf = []; } else buf.push(c); } },
});
console.log("fib(30) =", e.fib(30));
e.calcDemo();
