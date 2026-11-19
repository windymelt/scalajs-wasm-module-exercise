import sys
from wasmtime import Engine, Store, Module, Linker, FuncType, ValType, Config
cfg = Config()
for f in ["wasm_gc", "wasm_function_references", "wasm_exceptions"]:
    if hasattr(cfg, f):
        setattr(cfg, f, True)
eng = Engine(cfg); store = Store(eng)
mod = Module.from_file(eng, sys.argv[1])
linker = Linker(eng)
linker.define_func("host", "putChar", FuncType([ValType.i32()], []), lambda c: (sys.stdout.write(chr(c)), None)[1])
linker.define_func("host", "printInt", FuncType([ValType.i32()], []), lambda x: print("printInt:", x))
ex = linker.instantiate(store, mod).exports(store)
print("=== [wasmtime/Python] mandelbrot ===")
ex["mandelbrot"](store, 72, 24, 64)
print("=== [wasmtime/Python] calc ===")
ex["calcDemo"](store)
print("=== [wasmtime/Python] text ===")
data = (sys.argv[2] if len(sys.argv) > 2 else "寿司とビールと寿司とScala.js 🍣🍺").encode()
ex["inClear"](store)
for b in data:
    ex["inPush"](store, b)
n = ex["decorateBuffered"](store)
print(bytes(ex["outAt"](store, i) for i in range(n)).decode())
