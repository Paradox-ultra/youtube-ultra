package paradox.dl;
/** v10.21: the size of a googlevideo stream comes from its clen= (a "&range=0-0" probe answers with 1 byte and no Content-Range). */
public final class EngineTest {
  static int pass, fail;
  static void eq(String what, long want, long got){ if (want==got) pass++; else { fail++; System.out.println("FAIL "+what+": want "+want+" got "+got); } }
  public static void main(String[] a){
    eq("clen middle", 12345678L, Engine.clen("https://r1.googlevideo.com/videoplayback?expire=1&itag=137&clen=12345678&dur=10.0"));
    eq("clen first", 99L, Engine.clen("https://r1.googlevideo.com/videoplayback?clen=99&itag=140"));
    eq("no clen", -1L, Engine.clen("https://r1.googlevideo.com/videoplayback?itag=140"));
    eq("not a param", -1L, Engine.clen("https://r1.googlevideo.com/videoplayback?xclen=5"));
    eq("big (>4 GB)", 5000000000L, Engine.clen("https://x.googlevideo.com/videoplayback?a=1&clen=5000000000"));
    System.out.println("EngineTest: "+pass+" passed, "+fail+" failed"); if (fail>0) System.exit(1); }
}
