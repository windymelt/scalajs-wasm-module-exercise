import { readFileSync } from "node:fs";
const { instance: { exports: e } } = await WebAssembly.instantiate(readFileSync(process.argv[2]), {});
const read = (len, at) => { let s = ""; for (let i = 0; i < len(); i++) s += String.fromCharCode(at(i)); return s; };
export const run = (src, fuel = 50_000_000) => {
  e.inClear(); for (let i = 0; i < src.length; i++) e.inPush(src.charCodeAt(i));
  let status;
  try { status = e.run(fuel); } catch (err) { return { status: "trap", result: `${err.constructor.name}: ${err.message}` }; }
  return { status: ["ok", "error", "incomplete"][status], out: read(e.outputLen, e.outputAt), result: read(e.resultLen, e.resultAt), steps: e.steps() };
};
const cases = [
  "(+ 1 2 3)",
  "(define (fact n) (if (= n 0) 1 (* n (fact (- n 1))))) (fact 30)",
  "(fact 100)",
  "(/ 1 3) (/ 6 3) (exact->inexact 1/2)",
  "(/ 7 2) 1.0 (* 1.5 2) (sqrt 16) (sqrt 2) (expt 2 100) (expt 2 0.5)",
  "(define (loop i acc) (if (= i 0) acc (loop (- i 1) (+ acc i)))) (loop 1000000 0)",
  "(let loop ((i 0) (acc '())) (if (= i 5) (reverse acc) (loop (+ i 1) (cons (* i i) acc))))",
  "(define (make-counter) (let ((n 0)) (lambda () (set! n (+ n 1)) n))) (define c (make-counter)) (c) (c) (c)",
  "(map (lambda (x y) (* x y)) '(1 2 3) '(4 5 6)) (filter odd? (iota 10)) (fold-left + 0 (iota 101))",
  "(define xs '(3 1 4 1 5 9 2 6)) (define (qs l) (if (null? l) '() (let ((p (car l)) (r (cdr l))) (append (qs (filter (lambda (x) (< x p)) r)) (list p) (qs (filter (lambda (x) (>= x p)) r)))))) (qs xs)",
  "(for-each (lambda (s) (display s) (display \"、\")) '(\"寿司\" \"ビール\" \"Scala\")) (newline)",
  "`(1 ,(+ 1 1) ,@(map (lambda (x) (* x 10)) '(3 4)))",
  "(cond ((assq 'b '((a 1) (b 2))) => 1) (else 0))",
  "(cond ((assq 'b '((a 1) (b 2)))) (else 0))",
  "'(a . b) '(1 (2 3) . 4) \"he said \\\"hi\\\"\"",
  "(car '())",
  "(undefined-var)",
  "(+ 1 \"x\")",
  "((lambda (x) x))",
  "(define (f n) (if (= n 0) 0 (+ 1 (f (- n 1))))) (f 1000)",
  "(f 100000)",
  "(define (spin) (spin)) (spin)",
  "(define (g x)\n  (* x",
  "(+ 1 2))",
  "(let* ((x 1) (y (+ x 1))) (* x y)) (letrec ((ev? (lambda (n) (if (= n 0) #t (od? (- n 1))))) (od? (lambda (n) (if (= n 0) #f (ev? (- n 1)))))) (ev? 100001))",
  "(sin 0) (cos 0) (exp 1) (log 100) (round 2.5) (round 3.5) (modulo -7 3) (remainder -7 3)",
  "(random 100) (random 100) (random 100)",
  "(error \"だめでした\" 42 'foo)",
  "fact car",
];
for (const src of cases) {
  const r = run(src);
  console.log(`> ${src.replace(/\n/g, "⏎")}\n  [${r.status}${r.steps !== undefined ? `, ${r.steps} steps` : ""}] ${r.out ? `out=${JSON.stringify(r.out)} ` : ""}${r.result.replace(/\n/g, " | ")}`);
}
