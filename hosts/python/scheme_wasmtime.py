import sys, time
from wasmtime import Engine, Store, Module, Instance, Config
cfg = Config()
for f in ["wasm_gc", "wasm_function_references", "wasm_exceptions"]:
    if hasattr(cfg, f): setattr(cfg, f, True)
eng = Engine(cfg); store = Store(eng)
ex = Instance(store, Module.from_file(eng, sys.argv[1]), []).exports(store)
def run(src):
    ex["inClear"](store)
    units = src.encode("utf-16-le")
    for i in range(0, len(units), 2):
        ex["inPush"](store, int.from_bytes(units[i:i + 2], "little"))
    t0 = time.perf_counter(); st = ex["run"](store, 200_000_000); dt = time.perf_counter() - t0
    res = "".join(chr(ex["resultAt"](store, i)) for i in range(ex["resultLen"](store)))
    return st, res.replace("\n", " | "), dt
print(run("(define (fact n) (if (= n 0) 1 (* n (fact (- n 1))))) (fact 30)"))
print(run("(define (fib n) (if (< n 2) n (+ (fib (- n 1)) (fib (- n 2))))) (fib 25)"))
