import java.io.*;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/** The app's decoder path (TarXzExtractor: tukaani XZInputStream -> commons-compress tar), and zstd-jni for comparison.
 *  java -cp jars/* Bench.java xz|zstd <file> [tar]  -> one "JAVA ..." line. */
public class Bench {
    public static void main(String[] a) throws Exception {
        String kind = a[0], f = a[1];
        boolean walk = a.length > 2 && a[2].equals("tar");
        long t0 = System.nanoTime();
        InputStream raw = new BufferedInputStream(new FileInputStream(f), 1 << 16);
        InputStream in;
        if (kind.equals("xz")) in = new org.tukaani.xz.XZInputStream(raw, -1);
        else {
            com.github.luben.zstd.ZstdInputStream z = new com.github.luben.zstd.ZstdInputStream(raw);
            z.setLongMax(31);
            in = z;
        }
        long total = 0, n = 0;
        byte[] buf = new byte[1 << 16];
        if (walk) {
            TarArchiveInputStream t = new TarArchiveInputStream(new BufferedInputStream(in, 1 << 16));
            TarArchiveEntry e;
            while ((e = t.getNextEntry()) != null) {
                n++;
                int r;
                while ((r = t.read(buf)) > 0) total += r;
            }
        } else {
            int r;
            while ((r = in.read(buf)) > 0) total += r;
        }
        double s = (System.nanoTime() - t0) / 1e9;
        System.out.printf("JAVA %s %s walk=%b entries=%d bytes=%d secs=%.2f MBps=%.1f%n", kind, new File(f).getName(), walk, n, total, s, total / 1e6 / s);
    }
}
