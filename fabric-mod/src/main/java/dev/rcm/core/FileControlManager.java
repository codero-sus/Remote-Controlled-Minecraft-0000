package dev.rcm.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Full-featured file manager supporting directory browsing, file reading, creation,
 * full rewrites, in-place text edits, appends, Discord uploads/downloads, archiving,
 * copying, moving, and deletion.
 */
public final class FileControlManager {
	private final Path baseDir;
	private final RcmConfig config;

	public FileControlManager(Path baseDir, RcmConfig config) {
		this.baseDir = baseDir.toAbsolutePath().normalize();
		this.config = config;
	}

	public Path getBaseDir() {
		return baseDir;
	}

	public Path resolvePath(String rawPath) {
		if (rawPath == null || rawPath.trim().isEmpty() || ".".equals(rawPath.trim())) {
			return baseDir;
		}
		String cleaned = rawPath.trim();
		Path candidate = baseDir.resolve(cleaned).normalize();
		if (!config.isAllowUnrestrictedPaths() && !candidate.startsWith(baseDir)) {
			throw new SecurityException("Path outside game directory blocked by allowUnrestrictedPaths=false: " + rawPath);
		}
		return candidate;
	}

	public static String unwrapCodeBlock(String content) {
		if (content == null) {
			return "";
		}
		String trimmed = content.trim();
		if (trimmed.startsWith("```") && trimmed.endsWith("```") && trimmed.length() >= 6) {
			int firstNewline = trimmed.indexOf('\n');
			if (firstNewline > 0 && firstNewline < trimmed.length() - 3) {
				return trimmed.substring(firstNewline + 1, trimmed.length() - 3);
			}
		}
		return content;
	}

	public String listDirectory(String rawPath) throws IOException {
		Path target = resolvePath(rawPath);
		if (!Files.exists(target)) {
			return "Path does not exist: `" + target + "`";
		}
		if (!Files.isDirectory(target)) {
			return fileInfo(target);
		}
		List<Path> children = new ArrayList<Path>();
		for (Path p : Files.newDirectoryStream(target)) {
			children.add(p);
		}
		Collections.sort(children, new Comparator<Path>() {
			@Override
			public int compare(Path a, Path b) {
				boolean da = Files.isDirectory(a);
				boolean db = Files.isDirectory(b);
				if (da != db) {
					return da ? -1 : 1;
				}
				return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
			}
		});

		SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
		StringBuilder sb = new StringBuilder();
		sb.append("Directory: ").append(target).append(" (").append(children.size()).append(" entries)\n");
		int shown = 0;
		for (Path p : children) {
			if (shown >= 80) {
				sb.append("... and ").append(children.size() - shown).append(" more entries\n");
				break;
			}
			boolean dir = Files.isDirectory(p);
			long size = dir ? 0L : safeSize(p);
			String mod = fmt.format(new Date(safeLastModified(p)));
			sb.append(dir ? "[DIR]  " : "[FILE] ")
				.append(String.format("%-10s ", dir ? "-" : formatBytes(size)))
				.append(mod).append("  ")
				.append(p.getFileName()).append(dir ? "/" : "")
				.append('\n');
			shown++;
		}
		return sb.toString();
	}

	public String readFile(String rawPath, int startLine, int endLine) throws IOException {
		Path target = resolvePath(rawPath);
		if (!Files.exists(target)) {
			return "File does not exist: " + target;
		}
		if (Files.isDirectory(target)) {
			return "Target is a directory (use `files ls` instead): " + target;
		}
		byte[] bytes = Files.readAllBytes(target);
		String text = new String(bytes, StandardCharsets.UTF_8);
		String[] lines = text.split("\r?\n", -1);
		int from = Math.max(1, startLine);
		int to = endLine <= 0 ? Math.min(lines.length, from + 119) : Math.min(lines.length, endLine);
		StringBuilder sb = new StringBuilder();
		sb.append("File: ").append(target)
			.append(" (lines ").append(from).append("-").append(to)
			.append(" of ").append(lines.length).append(", ").append(formatBytes(bytes.length)).append(")\n");
		for (int i = from; i <= to; i++) {
			sb.append(String.format("%4d | %s\n", Integer.valueOf(i), lines[i - 1]));
		}
		return sb.toString();
	}

	public String writeFile(String rawPath, String rawContent) throws IOException {
		Path target = resolvePath(rawPath);
		if (target.getParent() != null) {
			Files.createDirectories(target.getParent());
		}
		String clean = unwrapCodeBlock(rawContent);
		byte[] bytes = clean.getBytes(StandardCharsets.UTF_8);
		Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		return "Wrote " + formatBytes(bytes.length) + " to `" + target + "`";
	}

	public String appendFile(String rawPath, String rawContent) throws IOException {
		Path target = resolvePath(rawPath);
		if (target.getParent() != null) {
			Files.createDirectories(target.getParent());
		}
		String clean = unwrapCodeBlock(rawContent);
		byte[] bytes = clean.getBytes(StandardCharsets.UTF_8);
		Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
		return "Appended " + formatBytes(bytes.length) + " to `" + target + "`";
	}

	public String editFile(String rawPath, String oldText, String newText) throws IOException {
		Path target = resolvePath(rawPath);
		if (!Files.exists(target) || Files.isDirectory(target)) {
			return "Editable file not found: `" + target + "`";
		}
		String existing = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
		String normalizedOld = unwrapCodeBlock(oldText);
		String normalizedNew = unwrapCodeBlock(newText);
		if (!existing.contains(normalizedOld)) {
			String lfExisting = existing.replace("\r\n", "\n");
			String lfOld = normalizedOld.replace("\r\n", "\n");
			if (!lfExisting.contains(lfOld)) {
				return "Target snippet not found in `" + target + "` (0 replacements made).";
			}
			String updated = lfExisting.replaceFirst(java.util.regex.Pattern.quote(lfOld),
				java.util.regex.Matcher.quoteReplacement(normalizedNew.replace("\r\n", "\n")));
			Files.write(target, updated.getBytes(StandardCharsets.UTF_8));
			return "Edited `" + target + "` successfully.";
		}
		String updated = existing.replaceFirst(java.util.regex.Pattern.quote(normalizedOld),
			java.util.regex.Matcher.quoteReplacement(normalizedNew));
		Files.write(target, updated.getBytes(StandardCharsets.UTF_8));
		return "Edited `" + target + "` successfully (" + formatBytes(updated.getBytes(StandardCharsets.UTF_8).length) + ").";
	}

	public String writeBinary(String rawPath, byte[] data) throws IOException {
		Path target = resolvePath(rawPath);
		if (target.getParent() != null) {
			Files.createDirectories(target.getParent());
		}
		Files.write(target, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		return "Saved uploaded file `" + target + "` (" + formatBytes(data.length) + ")";
	}

	public TransferPayload prepareDownload(String rawPath) throws IOException {
		final Path target = resolvePath(rawPath);
		if (!Files.exists(target)) {
			throw new IOException("Path does not exist: " + target);
		}
		if (!Files.isDirectory(target)) {
			return new TransferPayload(target.getFileName().toString(), Files.readAllBytes(target));
		}
		final ByteArrayOutputStream baos = new ByteArrayOutputStream();
		final ZipOutputStream zos = new ZipOutputStream(baos);
		try {
			Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					String rel = target.relativize(file).toString().replace('\\', '/');
					zos.putNextEntry(new ZipEntry(rel));
					zos.write(Files.readAllBytes(file));
					zos.closeEntry();
					return FileVisitResult.CONTINUE;
				}
			});
		} finally {
			zos.close();
		}
		String zipName = (target.getFileName() == null ? "archive" : target.getFileName().toString()) + ".zip";
		return new TransferPayload(zipName, baos.toByteArray());
	}

	public String mkdir(String rawPath) throws IOException {
		Path target = resolvePath(rawPath);
		Files.createDirectories(target);
		return "Created directory `" + target + "`";
	}

	public String move(String srcRaw, String dstRaw) throws IOException {
		Path src = resolvePath(srcRaw);
		Path dst = resolvePath(dstRaw);
		if (dst.getParent() != null) {
			Files.createDirectories(dst.getParent());
		}
		Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
		return "Moved `" + src + "` -> `" + dst + "`";
	}

	public String copy(String srcRaw, String dstRaw) throws IOException {
		final Path src = resolvePath(srcRaw);
		final Path dst = resolvePath(dstRaw);
		if (Files.isDirectory(src)) {
			Files.walkFileTree(src, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
					Files.createDirectories(dst.resolve(src.relativize(dir)));
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.copy(file, dst.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
					return FileVisitResult.CONTINUE;
				}
			});
		} else {
			if (dst.getParent() != null) {
				Files.createDirectories(dst.getParent());
			}
			Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
		}
		return "Copied `" + src + "` -> `" + dst + "`";
	}

	public String delete(String rawPath) throws IOException {
		Path target = resolvePath(rawPath);
		if (target.equals(baseDir)) {
			throw new SecurityException("Refusing to delete root game directory itself.");
		}
		if (!Files.exists(target)) {
			return "Nothing to delete at `" + target + "`";
		}
		if (Files.isDirectory(target)) {
			Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					Files.deleteIfExists(file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
					Files.deleteIfExists(dir);
					return FileVisitResult.CONTINUE;
				}
			});
		} else {
			Files.deleteIfExists(target);
		}
		return "Deleted `" + target + "`";
	}

	private String fileInfo(Path p) throws IOException {
		return "File: " + p + "\nSize: " + formatBytes(Files.size(p))
			+ "\nModified: " + new Date(Files.getLastModifiedTime(p).toMillis());
	}

	private static long safeSize(Path p) {
		try {
			return Files.size(p);
		} catch (IOException e) {
			return 0L;
		}
	}

	private static long safeLastModified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (IOException e) {
			return 0L;
		}
	}

	public static String formatBytes(long bytes) {
		if (bytes < 1024L) {
			return bytes + " B";
		}
		if (bytes < 1024L * 1024L) {
			return String.format("%.1f KB", Double.valueOf(bytes / 1024.0));
		}
		if (bytes < 1024L * 1024L * 1024L) {
			return String.format("%.2f MB", Double.valueOf(bytes / (1024.0 * 1024.0)));
		}
		return String.format("%.2f GB", Double.valueOf(bytes / (1024.0 * 1024.0 * 1024.0)));
	}

	public static final class TransferPayload {
		public final String fileName;
		public final byte[] data;

		public TransferPayload(String fileName, byte[] data) {
			this.fileName = fileName;
			this.data = data;
		}
	}
}
