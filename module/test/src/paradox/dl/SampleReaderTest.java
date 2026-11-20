package paradox.dl;

import java.io.*;
import java.util.*;

public final class SampleReaderTest {
  static int pass, fail;

  static void check(boolean ok, String label) {
    if (ok) pass++;
    else {
      fail++;
      System.out.println("FAIL: " + label);
    }
  }

  static byte[] pattern(int length) {
    byte[] data = new byte[length];
    for (int i = 0; i < length; i++)
      data[i] = (byte)((i * 31 + (i >>> 7) * 17) & 255);
    return data;
  }

  static File file(byte[] data) throws IOException {
    File f = File.createTempFile("sample-reader-", ".bin");
    FileOutputStream out = new FileOutputStream(f);
    try {
      out.write(data);
    } finally {
      out.close();
    }
    f.deleteOnExit();
    return f;
  }

  static byte[] read(Mp4Mux.SampleReader reader, long offset, int length,
      byte[] scratch, Mp4Mux.Progress progress) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    reader.copy(offset, length, out, scratch, progress, true);
    return out.toByteArray();
  }

  static boolean equalsRange(byte[] actual, byte[] source,
      int offset, int length) {
    if (actual.length != length) return false;
    for (int i = 0; i < length; i++)
      if (actual[i] != source[offset + i]) return false;
    return true;
  }

  static boolean hasMessage(IOException e, String expected) {
    return e.getMessage() != null && e.getMessage().contains(expected);
  }

  static void testWindowReuseAndRefill() throws Exception {
    byte[] source = pattern(1024 * 1024);
    File f = file(source);
    Mp4Mux.SampleReader r = new Mp4Mux.SampleReader(f);
    try {
      byte[] first = read(r, 100, 73, new byte[1024], null);
      check(equalsRange(first, source, 100, 73),
          "first sample copied exactly");

      long afterFirst = r.sourceReadCalls;

      byte[] nearby = read(r, 8192, 91, new byte[1024], null);
      check(equalsRange(nearby, source, 8192, 91),
          "nearby separated sample copied exactly");
      check(r.sourceReadCalls == afterFirst,
          "nearby separated sample served from cached window");

      byte[] backward = read(r, 50, 37, new byte[1024], null);
      check(equalsRange(backward, source, 50, 37),
          "backward offset copied exactly");
      check(r.sourceReadCalls > afterFirst,
          "backward offset outside window causes refill");

      long afterBackward = r.sourceReadCalls;
      byte[] refill = read(r, 400000, 127, new byte[1024], null);
      check(equalsRange(refill, source, 400000, 127),
          "sample after window boundary copied exactly");
      check(r.sourceReadCalls > afterBackward,
          "new window is refilled when offset is outside cache");
    } finally {
      r.close();
    }
  }

  static void testLargeSampleAndScratchValidation() throws Exception {
    byte[] source = pattern(900 * 1024);
    File f = file(source);
    Mp4Mux.SampleReader r = new Mp4Mux.SampleReader(f);
    try {
      int offset = 123;
      int length = Mp4Mux.SampleReader.WINDOW_SIZE + 100000;
      byte[] actual = read(r, offset, length, new byte[8192], null);
      check(equalsRange(actual, source, offset, length),
          "sample larger than window copied exactly");
      check(r.sourceReadCalls > 1,
          "large sample uses multiple bounded read calls");

      boolean rejected = false;
      try {
        read(r, 0, Mp4Mux.SampleReader.WINDOW_SIZE + 1,
            new byte[0], null);
      } catch (Mp4Mux.Failed e) {
        rejected = hasMessage(e, "empty sample copy buffer");
      }
      check(rejected, "zero-sized scratch buffer rejected");
    } finally {
      r.close();
    }
  }

  static void testZeroSmallAndBounds() throws Exception {
    byte[] source = pattern(4096);
    File f = file(source);
    Mp4Mux.SampleReader r = new Mp4Mux.SampleReader(f);
    try {
      byte[] zero = read(r, source.length, 0, new byte[0], null);
      check(zero.length == 0, "zero-length sample is a no-op at EOF");
      check(r.sourceReadCalls == 0, "zero-length sample performs no source read");

      byte[] one = read(r, 17, 1, new byte[1], null);
      check(equalsRange(one, source, 17, 1), "one-byte sample copied exactly");

      byte[] small = read(r, 200, 300, new byte[1024], null);
      check(equalsRange(small, source, 200, 300),
          "small sample copied exactly");

      boolean negative = false;
      try {
        read(r, 10, -1, new byte[32], null);
      } catch (Mp4Mux.Failed e) {
        negative = hasMessage(e, "negative sample size");
      }
      check(negative, "negative sample size rejected");

      boolean overflow = false;
      try {
        read(r, Long.MAX_VALUE, 10, new byte[32], null);
      } catch (Mp4Mux.Failed e) {
        overflow = hasMessage(e, "shorter than its index");
      }
      check(overflow, "overflowing sample range rejected");

      boolean beyond = false;
      try {
        read(r, source.length + 1L, 1, new byte[32], null);
      } catch (Mp4Mux.Failed e) {
        beyond = hasMessage(e, "shorter than its index");
      }
      check(beyond, "sample beyond cached source length rejected");
    } finally {
      r.close();
    }
  }

  static void testTruncationAfterOpen() throws Exception {
    File f = file(pattern(4096));
    Mp4Mux.SampleReader r = new Mp4Mux.SampleReader(f);
    try {
      RandomAccessFile truncate = new RandomAccessFile(f, "rw");
      try {
        truncate.setLength(128);
      } finally {
        truncate.close();
      }

      boolean clearError = false;
      try {
        read(r, 0, 512, new byte[1024], null);
      } catch (Mp4Mux.Failed e) {
        clearError = hasMessage(e, "shorter than its index");
      }
      check(clearError,
          "truncation after reader construction retains clear index error");
    } finally {
      r.close();
      f.delete();
    }
  }

  static void testCancellationDuringLargeSample() throws Exception {
    byte[] source = pattern(8 * 1024 * 1024);
    File f = file(source);
    Mp4Mux.SampleReader r = new Mp4Mux.SampleReader(f);
    final int[] checks = {0};
    Mp4Mux.Progress progress = new Mp4Mux.Progress() {
      public void at(long done, long total) {}
      public boolean cancelled() {
        checks[0]++;
        return checks[0] >= 3;
      }
    };

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    boolean cancelled = false;
    try {
      r.copy(0, 4L * 1024 * 1024, out, new byte[1024 * 1024],
          progress, true);
    } catch (InterruptedIOException e) {
      cancelled = "cancelled".equals(e.getMessage());
    } finally {
      r.close();
    }

    check(cancelled, "cancellation interrupts a large sample extent");
    check(out.size() == 1024 * 1024,
        "large extent stops after the first bounded read");
    check(checks[0] >= 3, "cancellation checked between bounded reads");
  }

  static Mp4Mux.Track fixtureTrack(File f) {
    Mp4Mux.Track t = new Mp4Mux.Track();
    t.video = true;
    t.codec = "avc1";
    t.timescale = 1000;
    t.width = 640;
    t.height = 360;
    t.file = f;
    t.entry = new byte[] {0, 0, 0, 8, 'a', 'v', 'c', '1'};
    t.lastDur = 40;

    int[] offsets = {100, 9000, 17000, 28000, 29000, 90000, 180000};
    int[] sizes   = {300,  700,  1100,   200,  4000,  8192,  1024};

    for (int i = 0; i < offsets.length; i++)
      t.add(offsets[i], sizes[i], i * 40L, 0, i == 0 || i == 5);

    return t;
  }

  /*
   * Reference implementation of the original pre-read-ahead write path.
   * Metadata, chunk scheduling, and sample coalescing intentionally mirror
   * the original Mp4Mux.write implementation.
   */
  static byte[] legacyWrite(Mp4Mux.Track[] ts) throws IOException {
    List<Mp4Mux.Chunk> chunks = new ArrayList<Mp4Mux.Chunk>();
    int[] next = new int[ts.length];

    while (true) {
      int pick = -1;
      double best = Double.MAX_VALUE;
      for (int k = 0; k < ts.length; k++) {
        if (next[k] < ts[k].n) {
          double s = (ts[k].dts[next[k]] + ts[k].startTicks)
              / (double)ts[k].timescale;
          if (s < best) {
            best = s;
            pick = k;
          }
        }
      }
      if (pick < 0) break;

      Mp4Mux.Track t = ts[pick];
      int first = next[pick];
      double limit = best + 1.0;
      int count = 0;
      while (first + count < t.n && count < 4096 &&
          (t.dts[first + count] + t.startTicks) / (double)t.timescale < limit)
        count++;
      if (count == 0) count = 1;

      chunks.add(new Mp4Mux.Chunk(pick, first, count));
      next[pick] += count;
    }

    long payload = 0;
    for (Mp4Mux.Track t : ts) payload += t.bytes();

    byte[] ftyp = Mp4Mux.ftyp(ts);
    boolean big = payload + ftyp.length + 16 +
        (long)ts.length * 1024 * 1024 > 0xFFFFFFFFL - 1024;
    int mdatHeader = payload + 8 > 0xFFFFFFFFL ? 16 : 8;

    byte[] moov = Mp4Mux.moov(ts, chunks, 0, big);
    long dataStart = ftyp.length + moov.length + mdatHeader;
    moov = Mp4Mux.moov(ts, chunks, dataStart, big);
    if (ftyp.length + moov.length + mdatHeader != dataStart)
      throw new IOException("reference index size changed");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(ftyp);
    out.write(moov);

    Mp4Mux.B header = new Mp4Mux.B();
    if (mdatHeader == 16) {
      header.u32(1);
      header.four("mdat");
      header.u64(16 + payload);
    } else {
      header.u32(8 + payload);
      header.four("mdat");
    }
    out.write(header.toArray());

    RandomAccessFile[] sources = new RandomAccessFile[ts.length];
    byte[] buffer = new byte[1 << 20];

    try {
      for (int k = 0; k < ts.length; k++)
        sources[k] = new RandomAccessFile(ts[k].file, "r");

      for (Mp4Mux.Chunk c : chunks) {
        Mp4Mux.Track t = ts[c.track];
        RandomAccessFile source = sources[c.track];
        int i = c.first;
        int end = c.first + c.count;

        while (i < end) {
          long offset = t.off[i];
          long length = t.size[i];
          int j = i + 1;

          while (j < end && t.off[j] == offset + length &&
              length < 64L << 20) {
            length += t.size[j];
            j++;
          }

          source.seek(offset);
          long left = length;
          while (left > 0) {
            int n = source.read(buffer, 0,
                (int)Math.min((long)buffer.length, left));
            if (n < 0)
              throw new Mp4Mux.Failed("the downloaded " +
                  (t.video ? "video" : "audio") +
                  " file is shorter than its index (download incomplete?)");
            out.write(buffer, 0, n);
            left -= n;
          }
          i = j;
        }
      }
    } finally {
      for (RandomAccessFile source : sources)
        if (source != null) {
          try {
            source.close();
          } catch (IOException ignored) {}
        }
    }

    out.flush();
    return out.toByteArray();
  }

  static void testFullMuxByteParity() throws Exception {
    byte[] source = pattern(256 * 1024);
    File f = file(source);

    Mp4Mux.Track baselineTrack = fixtureTrack(f);
    Mp4Mux.Track candidateTrack = fixtureTrack(f);

    byte[] baseline = legacyWrite(new Mp4Mux.Track[] {baselineTrack});

    ByteArrayOutputStream candidateOut = new ByteArrayOutputStream();
    Mp4Mux.write(new Mp4Mux.Track[] {candidateTrack}, candidateOut, null);
    byte[] candidate = candidateOut.toByteArray();

    check(Arrays.equals(candidate, baseline),
        "complete MP4 output byte-for-byte identical to original writer");

    f.delete();
  }

  public static void main(String[] args) throws Exception {
    testWindowReuseAndRefill();
    testLargeSampleAndScratchValidation();
    testZeroSmallAndBounds();
    testTruncationAfterOpen();
    testCancellationDuringLargeSample();
    testFullMuxByteParity();

    System.out.println("SampleReaderTest: " + pass +
        " passed, " + fail + " failed");
    if (fail > 0) System.exit(1);
  }
}
