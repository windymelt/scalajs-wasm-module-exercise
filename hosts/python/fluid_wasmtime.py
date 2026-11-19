import sys, time, math
from wasmtime import Engine, Store, Module, Instance, Config
cfg = Config()
for f in ["wasm_gc", "wasm_function_references", "wasm_exceptions"]:
    if hasattr(cfg, f): setattr(cfg, f, True)
eng = Engine(cfg); store = Store(eng)
ex = Instance(store, Module.from_file(eng, sys.argv[1]), []).exports(store)
NX, NY, STEPS = 256, 160, 300
ex["fluidInit"](store, NX, NY)
total = 0.0
for t in range(1, STEPS + 1):
    a = t * 0.02
    ex["fluidSplat"](store, 30.0, NY - 20.0, 2.2, -2.6, 0.9, 0.25 + 0.2 * math.sin(a), 0.1, 7.0)
    ex["fluidSplat"](store, NX - 30.0, NY - 20.0, -2.2, -2.6, 0.1, 0.35, 0.95, 7.0)
    t0 = time.perf_counter(); ex["fluidStep"](store, 1.0, 20, 0.35, 0.995); total += time.perf_counter() - t0
print(f"wasmtime: {total / STEPS * 1000:.2f} ms/step, energy at {STEPS} = {ex['fluidEnergy'](store):.1f}")
