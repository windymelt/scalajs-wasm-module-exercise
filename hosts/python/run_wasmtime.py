import sys, wasmtime
from wasmtime import Engine, Store, Module, Linker, FuncType, ValType, Config
cfg = Config()
for f in ["wasm_gc", "wasm_function_references", "wasm_exceptions", "wasm_tail_call"]:
    if hasattr(cfg, f):
        setattr(cfg, f, True)
eng = Engine(cfg)
store = Store(eng)
mod = Module.from_file(eng, sys.argv[1])
linker = Linker(eng)
linker.define_func("host", "printInt", FuncType([ValType.i32()], []), lambda x: print("printInt (from Python):", x))
inst = linker.instantiate(store, mod)
ex = inst.exports(store)
print("fib(30) =", ex["fib"](store, 30))
ex["countdown"](store, 3)
print("addLong =", ex["addLong"](store, 2**62, 2**62))
print("hypot(3,4) =", ex["hypot"](store, 3.0, 4.0))
