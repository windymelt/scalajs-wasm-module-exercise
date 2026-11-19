import sys, time, struct, hashlib
from wasmtime import Engine, Store, Module, Linker, FuncType, ValType, Config
cfg = Config()
for f in ["wasm_gc", "wasm_function_references", "wasm_exceptions"]:
    if hasattr(cfg, f):
        setattr(cfg, f, True)
eng = Engine(cfg); store = Store(eng)
linker = Linker(eng)
for name in ["printInt", "putChar"]:
    linker.define_func("host", name, FuncType([ValType.i32()], []), lambda x: None)
ex = linker.instantiate(store, Module.from_file(eng, sys.argv[1])).exports(store)
out = sys.argv[2]

def save(name, n):
    data = b"".join(struct.pack("<i", ex["pngWord"](store, i)) for i in range((n + 3) // 4))[:n]
    open(f"{out}/{name}", "wb").write(data)
    print(f"  -> {name} sha256={hashlib.sha256(data).hexdigest()[:16]}")

for label, name, f in [("rayTrace 640x400 spp=16", "raytrace_wasmtime.png", lambda: ex["rayTrace"](store, 640, 400, 16, 42)),
                       ("julia 640x400", "julia_wasmtime.png", lambda: ex["julia"](store, 640, 400, -0.8, 0.156))]:
    t0 = time.perf_counter(); n = f(); print(f"{label}: {(time.perf_counter() - t0) * 1000:.0f} ms")
    save(name, n)
