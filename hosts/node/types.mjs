import { readFileSync } from "node:fs";
const { instance } = await WebAssembly.instantiate(readFileSync(process.argv[2]), {
  host: { printInt: (x) => console.log("printInt:", x), putChar: () => {} },
});
const e = instance.exports;
const t = (label, f) => { try { console.log(label, "=>", f()); } catch (err) { console.log(label, "=> THROWS", err.constructor.name + ":", err.message); } };
t("addLong(2n**62n, 2n**62n)  [overflow]", () => e.addLong(2n ** 62n, 2n ** 62n));
t("addLong(1, 2)  [Number instead of BigInt]", () => e.addLong(1, 2));
t("hypot(3, 4)", () => e.hypot(3, 4));
t("halfF(0.1)", () => e.halfF(0.1));
const sq = e.makeSquares(5);
t("makeSquares(5)", () => [typeof sq, Object.prototype.toString.call(sq), JSON.stringify(sq), sq.length, sq[0]]);
t("sumArray(makeSquares(5))", () => e.sumArray(sq));
t("sumArray([1,2,3])", () => e.sumArray([1, 2, 3]));
t("sumArray(new Int32Array([1,2,3]))", () => e.sumArray(new Int32Array([1, 2, 3])));
t("sumArray(null)", () => e.sumArray(null));
t("arrayLen(makeSquares(5))  [Array[Int] as Array[Double]]", () => e.arrayLen(sq));
