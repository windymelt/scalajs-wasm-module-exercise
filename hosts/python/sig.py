import sys, wasmtime
cfg = wasmtime.Config()
for f in ["wasm_gc", "wasm_function_references", "wasm_exceptions", "wasm_tail_call"]:
    if hasattr(cfg, f): setattr(cfg, f, True)
eng = wasmtime.Engine(cfg)
m = wasmtime.Module.from_file(eng, sys.argv[1])
for i in m.imports: print("import", i.module, i.name, [str(p) for p in i.type.params], "->", [str(r) for r in i.type.results])
for e in m.exports: print("export", e.name, [str(p) for p in e.type.params], "->", [str(r) for r in e.type.results])
