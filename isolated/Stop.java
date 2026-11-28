public class Stop { public static void main(String[] a) throws Exception {
  Thread t = new Thread(() -> { long i = 0; while (i >= 0) i = (i + 1) % 1000; }); t.setDaemon(true); t.start();
  Thread.sleep(200); t.interrupt(); Thread.sleep(200); System.out.println("interrupt 後も動いている: " + t.isAlive());
  try { t.stop(); } catch (Throwable e) { System.out.println("Thread.stop: " + e); }
}}
