package paradox.dl;

import java.io.*;

public final class SampleReaderBenchmark {
  static final int SAMPLE_COUNT = 2048;
  static final int SAMPLE_SIZE = 4096;
  static final int STRIDE = 8192;
  static final int FILE_SIZE = SAMPLE_COUNT * STRIDE + SAMPLE_SIZE;
  static final int ITERATIONS = 5;

  static final OutputStream NULL_OUT = new OutputStream() {
    public void write(int b) {}
    public void write(byte[] b, int off, int len) {}
  };

  static File makeFixture() throws IOException {
    File f = File.createTempFile("sample-reader-bench-", ".bin");
    RandomAccessFile out = new RandomAccessFile(f, "rw");
    try {
      out.setLength(FILE_SIZE);
      byte[] block = new byte[64 * 1024];
      long pos = 0;
      while (pos < FILE_SIZE) {
        int count = (int)Math.min(block.length, FILE_SIZE - pos);
        for (int i = 0; i < count; i++)
          block[i] = (byte)((pos + i) * 13 + ((pos + i) >>> 8));
        out.seek(pos);
        out.write(block, 0, count);
        pos += count;
      }
    } finally {
      out.close();
    }
    f.deleteOnExit();
    return f;
  }

  static final class Result {
    long elapsedNanos;
    long readCalls;
    long bytesCopied;
  }

  static Result runLegacy(File f) throws IOException {
    Result result = new Result();
    byte[] buffer = new byte[1 << 20];
    RandomAccessFile source = new RandomAccessFile(f, "r");
    long start = System.nanoTime();

    try {
      for (int i = 0; i < SAMPLE_COUNT; i++) {
        long offset = (long)i * STRIDE;
        long left = SAMPLE_SIZE;
        source.seek(offset);

        while (left > 0) {
          int n = source.read(buffer, 0,
              (int)Math.min((long)buffer.length, left));
          if (n <= 0) throw new EOFException("benchmark fixture truncated");
          result.readCalls++;
          NULL_OUT.write(buffer, 0, n);
          result.bytesCopied += n;
          left -= n;
        }
      }
    } finally {
      source.close();
    }

    result.elapsedNanos = System.nanoTime() - start;
    return result;
  }

  static Result runCandidate(File f) throws IOException {
    Result result = new Result();
    Mp4Mux.SampleReader source = new Mp4Mux.SampleReader(f);
    long start = System.nanoTime();

    try {
      byte[] scratch = new byte[1 << 20];
      for (int i = 0; i < SAMPLE_COUNT; i++) {
        long offset = (long)i * STRIDE;
        source.copy(offset, SAMPLE_SIZE, NULL_OUT, scratch, null, true);
        result.bytesCopied += SAMPLE_SIZE;
      }
      result.readCalls = source.sourceReadCalls;
    } finally {
      source.close();
    }

    result.elapsedNanos = System.nanoTime() - start;
    return result;
  }

  static long median(long[] values) {
    long[] copy = values.clone();
    java.util.Arrays.sort(copy);
    return copy[copy.length / 2];
  }

  public static void main(String[] args) throws Exception {
    File fixture = makeFixture();

    // Warm both paths before collecting timings.
    runLegacy(fixture);
    runCandidate(fixture);

    long[] legacyTimes = new long[ITERATIONS];
    long[] candidateTimes = new long[ITERATIONS];
    Result legacy = null;
    Result candidate = null;

    for (int i = 0; i < ITERATIONS; i++) {
      legacy = runLegacy(fixture);
      candidate = runCandidate(fixture);
      legacyTimes[i] = legacy.elapsedNanos;
      candidateTimes[i] = candidate.elapsedNanos;
    }

    System.out.println("Fixture bytes: " + FILE_SIZE);
    System.out.println("Samples: " + SAMPLE_COUNT);
    System.out.println("Bytes copied per run: " + legacy.bytesCopied);
    System.out.println("Legacy Java read() calls: " + legacy.readCalls);
    System.out.println("Candidate Java read() calls: " + candidate.readCalls);
    System.out.println("Legacy median ms: " +
        (median(legacyTimes) / 1_000_000.0));
    System.out.println("Candidate median ms: " +
        (median(candidateTimes) / 1_000_000.0));
    System.out.println("Note: read() calls are not physical storage I/O counts.");

    fixture.delete();
  }
}
