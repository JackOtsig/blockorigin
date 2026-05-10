package origin.jack.blockorigin.internal;

import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads a dimension's {@code region/} directory and returns the chunk positions
 * that have actual saved data in the {@code .mca} files. Skips empty slots so we
 * don't accidentally trigger generation of never-touched chunks during backfill.
 *
 * <p>Anvil region format: each {@code .mca} file is 32×32 chunks. The first
 * 4096 bytes are a location table — 1024 entries of (3-byte sector offset +
 * 1-byte sector count). A slot with both fields zero means "chunk not present
 * in this region file."
 */
public final class RegionScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger("blockorigin.regionscan");
    private static final Pattern MCA_NAME = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");

    private RegionScanner() {}

    public static List<ChunkPos> scan(Path regionDir) throws IOException {
        List<ChunkPos> result = new ArrayList<>();
        if (!Files.isDirectory(regionDir)) return result;
        try (Stream<Path> stream = Files.list(regionDir)) {
            stream.forEach(file -> {
                Matcher m = MCA_NAME.matcher(file.getFileName().toString());
                if (!m.matches()) return;
                int rx = Integer.parseInt(m.group(1));
                int rz = Integer.parseInt(m.group(2));
                try {
                    scanRegion(file, rx, rz, result);
                } catch (IOException e) {
                    LOGGER.warn("Failed to scan region file {}: {}", file, e.getMessage());
                }
            });
        }
        return result;
    }

    private static void scanRegion(Path mcaFile, int rx, int rz, List<ChunkPos> out) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(mcaFile.toFile(), "r")) {
            byte[] header = new byte[4096];
            int read = 0;
            while (read < 4096) {
                int n = raf.read(header, read, 4096 - read);
                if (n < 0) return;
                read += n;
            }
            for (int i = 0; i < 1024; i++) {
                int off = i * 4;
                int sectorOffset = ((header[off] & 0xFF) << 16)
                        | ((header[off + 1] & 0xFF) << 8)
                        | (header[off + 2] & 0xFF);
                int sectorCount = header[off + 3] & 0xFF;
                if (sectorOffset == 0 && sectorCount == 0) continue;
                int cxLocal = i & 31;
                int czLocal = (i >> 5) & 31;
                out.add(new ChunkPos((rx << 5) | cxLocal, (rz << 5) | czLocal));
            }
        }
    }
}
