import sys, time
from wasmtime import Engine, Store, Module, Linker, FuncType, ValType, Config
cfg = Config()
for f in ["wasm_gc", "wasm_function_references", "wasm_exceptions"]:
    if hasattr(cfg, f): setattr(cfg, f, True)

eng = Engine(cfg); store = Store(eng); linker = Linker(eng)
for name in ["printInt", "putChar"]:
    linker.define_func("host", name, FuncType([ValType.i32()], []), lambda x: None)
ex = linker.instantiate(store, Module.from_file(eng, sys.argv[1])).exports(store)
for f in ["benchArith", "benchArray", "benchAlloc"]:
    ex[f](store, 100000); t0 = time.perf_counter(); ex[f](store, 20_000_000)
    print("wasmtime", f, f"{(time.perf_counter() - t0) * 1000:.0f} ms")
