package org.hilel14.iceberg;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

/**
 *
 * @author hilel14
 */
public class ZipTool {

    static final Logger LOGGER = Logger.getLogger(ZipTool.class.getName());
    final private Path workFolder;
    final private String jobName;
    final private Path sourceFolder;
    final private Pattern excludeFilter;
    final private Set<String> history = new HashSet<>();
    final private Snapshot snapshot = new Snapshot();
    private long fileCount = 0;

    public ZipTool(Path workFolder, String jobName,
            Path sourceFolder, Pattern excludeFilter)
            throws Exception {
        this.workFolder = workFolder;
        this.jobName = jobName;
        this.sourceFolder = sourceFolder;
        this.excludeFilter = excludeFilter;
        loadHistory();
    }

    private Path getHistoryPath() {
        return workFolder.resolve(jobName + ".history");
    }

    private void loadHistory() throws Exception {
        Path path = getHistoryPath();
        if (Files.exists(path)) {
            LOGGER.log(Level.INFO, "loading history from file {0}", path);
            List<String> lines = Files.readAllLines(path);
            lines.forEach((line) -> {
                history.add(line);
            });
        } else {
            LOGGER.log(Level.INFO, "history file {0} not found, assuming full backup", path);
        }
    }

    public Path createArchive()
            throws Exception {
        LOGGER.log(Level.INFO, "Collecting files from {0} excluding pattern {1}",
                new Object[]{sourceFolder, excludeFilter.pattern()});
        Path target = workFolder.resolve(jobName + ".zip");
        Files.deleteIfExists(target);
        LOGGER.log(Level.INFO, "Adding files to {0}", target);
        try (ZipArchiveOutputStream zipStream = new ZipArchiveOutputStream(target.toFile())) {
            Files.walkFileTree(sourceFolder, new ZipTool.Zipper(zipStream));
            LOGGER.log(Level.INFO, "{0} files added, archive size is {1} bytes",
                    new Object[]{fileCount, Files.size(target)});
            // add snapshot file to archive
            if (fileCount > 0) {
                Path snapshotFile = workFolder.resolve("snapshot.json");
                snapshot.save(snapshotFile);
                addToZip(snapshotFile, snapshotFile.getFileName().toString(), zipStream);
                Files.delete(snapshotFile);
            }
        }
        // save history and return
        Files.write(getHistoryPath(), history, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return target;
    }

    private void addToZip(Path source, String entryName, ZipArchiveOutputStream outStream) throws IOException {
        ZipArchiveEntry entry = new ZipArchiveEntry(entryName);
        entry.setSize(Files.size(source));
        outStream.putArchiveEntry(entry);
        byte[] data = new byte[Files.size(source) > 1024 ? 1024 : (int) Files.size(source)];
        try (InputStream inStream = new FileInputStream(source.toFile())) {
            while (inStream.read(data) > 0) {
                outStream.write(data);
            }
        }
        outStream.closeArchiveEntry();
    }

    class Zipper extends SimpleFileVisitor<Path> {

        ZipArchiveOutputStream zipStream;

        public Zipper(ZipArchiveOutputStream zipStream) {
            this.zipStream = zipStream;
        }

        @Override
        public FileVisitResult visitFile(Path path, BasicFileAttributes attrs)
                throws IOException {
            // exclude
            if (excludeFilter.matcher(path.getFileName().toString()).matches()) {
                return FileVisitResult.CONTINUE;
            }
            // calculate file hash
            String hash;
            try (InputStream in = new FileInputStream(path.toFile())) {
                hash = DigestUtils.md5Hex(in);
            }
            // add new files to archive
            if (!history.contains(hash)) {
                addToZip(path, getRelativePath(path).toString(), zipStream);
                history.add(hash);
                fileCount++;
            }
            // update snapshot and continue
            snapshot.add(hash, getRelativePath(path));
            return FileVisitResult.CONTINUE;
        }

        private Path getRelativePath(Path p) {
            return sourceFolder.getFileName().resolve(sourceFolder.relativize(p));
        }
    }

    /**
     * @return the fileCount
     */
    public long getFileCount() {
        return fileCount;
    }
}