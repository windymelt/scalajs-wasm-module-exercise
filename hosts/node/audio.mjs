// インパルス応答エフェクターの数値検証
import { readFileSync } from "node:fs";
const { instance: { exports: e } } = await WebAssembly.instantiate(readFileSync(process.argv[2]), {});
const SR = e.sampleRate();

// 1. FFT 畳み込みと直接計算の一致 (wet = 1 で、RMS 合わせの倍率を最小二乗で推定して比べる)
{
  let seed = 1; const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647 * 2 - 1;
  const a = Array.from({ length: 3001 }, rnd), b = Array.from({ length: 517 }, rnd);
  e.inBegin(1, a.length); a.forEach((v, i) => e.inSet(0, i, v));
  e.irBegin(1, b.length); b.forEach((v, i) => e.irSet(0, i, v));
  const n = e.process(1.0);
  const direct = new Float64Array(a.length + b.length - 1);
  for (let i = 0; i < a.length; i++) for (let j = 0; j < b.length; j++) direct[i + j] += Math.fround(a[i]) * Math.fround(b[j]);
  let num = 0, den = 0; for (let i = 0; i < n; i++) { const o = e.outAt(0, i); num += o * direct[i]; den += direct[i] * direct[i]; }
  const k = num / den; let maxErr = 0, peak = 0;
  for (let i = 0; i < n; i++) { maxErr = Math.max(maxErr, Math.abs(e.outAt(0, i) - k * direct[i])); peak = Math.max(peak, Math.abs(k * direct[i])); }
  console.log(`1. convolve: length ${n} (expected ${direct.length}), FFT size ${e.fftSize()}, max error / peak = ${(maxErr / peak).toExponential(2)}`);
}

// 2. 残響時間: シュレーダー積分の -5 dB〜-35 dB の傾きから T60 を推定
for (const [kind, name, t60] of [[0, "小部屋", 0.45], [1, "ホール", 2.2], [2, "大聖堂", 6.0], [5, "金属の筒", 0.8]]) {
  const len = e.makeIr(kind, t60);
  const en = new Float64Array(len);
  for (let i = 0; i < len; i++) { const v = e.irAt(0, i); en[i] = v * v; }
  const sch = new Float64Array(len); let acc = 0;
  for (let i = len - 1; i >= 0; i--) { acc += en[i]; sch[i] = acc; }
  const db = (i) => 10 * Math.log10(sch[i] / sch[0]);
  let i5 = 0; while (db(i5) > -5) i5++;
  let i35 = i5; while (i35 < len - 1 && db(i35) > -35) i35++;
  const slope = (db(i35) - db(i5)) / ((i35 - i5) / SR);
  console.log(`2. ${name}: 指定 T60 = ${t60} s → 測定 ${(-60 / slope).toFixed(2)} s (長さ ${(len / SR).toFixed(2)} s)`);
}

// 3. 電話の周波数特性
{
  e.makeIr(4, 0); const P = 200; e.spectrum(P);
  const at = (f) => { const p = Math.round(Math.log(f / 20) / Math.log(1000) * (P - 1)); return e.specAt(p).toFixed(1); };
  console.log(`3. 電話: 100 Hz ${at(100)} dB, 300 Hz ${at(300)} dB, 1 kHz ${at(1000)} dB, 3.4 kHz ${at(3400)} dB, 8 kHz ${at(8000)} dB`);
}

// 4. 速さ: 20 秒ステレオの素材に 8 秒の応答 (上限いっぱい)
{
  const n = e.inBegin(2, 20 * SR); let s = 7;
  const t0 = performance.now();
  for (let ch = 0; ch < 2; ch++) for (let i = 0; i < n; i++) { s = (s * 16807) % 2147483647; e.inSet(ch, i, (s / 2147483647 - 0.5) * 0.2); }
  const t1 = performance.now();
  e.makeIr(2, 7.5);
  const t2 = performance.now();
  const outN = e.process(0.4);
  const t3 = performance.now();
  let sum = 0; for (let ch = 0; ch < 2; ch++) for (let i = 0; i < outN; i++) sum += e.outAt(ch, i);
  const t4 = performance.now();
  console.log(`4. 20 s + 7.9 s: 送る ${(t1 - t0).toFixed(0)} ms, 応答を作る ${(t2 - t1).toFixed(0)} ms, 畳み込み ${(t3 - t2).toFixed(0)} ms (FFT ${e.fftSize()}), 受け取る ${(t4 - t3).toFixed(0)} ms`);
}

// 5. 既定の組み合わせ
{
  const n = e.loadSample(0); e.makeIr(1, 2.2);
  const t0 = performance.now(); const outN = e.process(0.4); const ms = performance.now() - t0;
  let peak = 0; for (let i = 0; i < outN; i++) peak = Math.max(peak, Math.abs(e.outAt(0, i)));
  console.log(`5. 撥弦 ${(n / SR).toFixed(2)} s × ホール: 畳み込み ${ms.toFixed(0)} ms, 出力 ${(outN / SR).toFixed(2)} s, peak ${peak.toFixed(3)}`);
}
