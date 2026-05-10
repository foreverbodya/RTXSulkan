package com.sulkan.shaders.pack;

import com.sulkan.shaders.config.ShaderConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;

public final class ShaderPackScanner {
	private static final String SHADER_DIRECTORY_NAME = "shaders";

	private ShaderPackScanner() {
	}

	public record UnsupportedShaderPack(String displayName, String reason, Path path) {
	}

	public record ScanResult(List<AvailableShaderPack> supportedPacks, List<UnsupportedShaderPack> unsupportedPacks) {
	}

	public static Path shaderPackDirectory() {
		return FabricLoader.getInstance().getGameDir().resolve(SHADER_DIRECTORY_NAME);
	}

	public static void ensureShaderPackDirectory() {
		try {
			Files.createDirectories(shaderPackDirectory());
		} catch (IOException exception) {
			throw new IllegalStateException("Failed to create Sulkan shader pack directory", exception);
		}
	}

	public static List<AvailableShaderPack> listAvailablePacks() {
		return scan().supportedPacks();
	}

	public static ScanResult scan() {
		ensureShaderPackDirectory();
		List<AvailableShaderPack> supported = new ArrayList<>();
		List<UnsupportedShaderPack> unsupported = new ArrayList<>();
		try (Stream<Path> stream = Files.list(shaderPackDirectory())) {
			stream.sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
					.forEach(path -> classify(path, supported, unsupported));
		} catch (IOException exception) {
			unsupported.add(new UnsupportedShaderPack(shaderPackDirectory().toString(), "unreadable-folder", shaderPackDirectory()));
		}
		return new ScanResult(List.copyOf(supported), List.copyOf(unsupported));
	}

	public static boolean isKnownPackId(String packId, List<AvailableShaderPack> packs) {
		if (ShaderConfig.BUILTIN_PACK_ID.equals(packId)) {
			return true;
		}

		for (AvailableShaderPack pack : packs) {
			if (pack.id().equals(packId)) {
				return true;
			}
		}
		return false;
	}

	public static Path mapEntry(String rawEntryName) {
		String entryName = normalizeEntryName(rawEntryName);
		if (entryName.isEmpty() || entryName.contains("..")) {
			return null;
		}

		String trimmedEntry = trimLeadingContainerDirectory(entryName);
		if (trimmedEntry.startsWith("assets/")) {
			return Path.of(trimmedEntry);
		}
		if (trimmedEntry.startsWith("shaders/")) {
			return Path.of("assets", "sulkan").resolve(trimmedEntry);
		}
		if (trimmedEntry.equals("pack.png")) {
			return Path.of("pack.png");
		}
		return null;
	}

	private static void classify(Path path, List<AvailableShaderPack> supported, List<UnsupportedShaderPack> unsupported) {
		String fileName = path.getFileName().toString();
		if (!isCandidatePack(path)) {
			unsupported.add(new UnsupportedShaderPack(fileName, "not-a-shader-pack", path));
			return;
		}

		if (containsSupportedShaders(path)) {
			supported.add(toAvailablePack(path));
		} else {
			unsupported.add(new UnsupportedShaderPack(fileName, "missing-assets-sulkan-shaders", path));
		}
	}

	private static boolean isCandidatePack(Path path) {
		if (Files.isDirectory(path)) {
			return true;
		}

		String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
		return Files.isRegularFile(path) && fileName.endsWith(".zip");
	}

	private static AvailableShaderPack toAvailablePack(Path path) {
		String fileName = path.getFileName().toString();
		String displayName = fileName.endsWith(".zip") ? fileName.substring(0, fileName.length() - 4) : fileName;
		return new AvailableShaderPack(fileName, displayName, path, Files.isDirectory(path));
	}

	private static boolean containsSupportedShaders(Path path) {
		try {
			if (Files.isDirectory(path)) {
				return directoryContainsSupportedShaders(path);
			}
			return archiveContainsSupportedShaders(path);
		} catch (IOException exception) {
			return false;
		}
	}

	private static boolean directoryContainsSupportedShaders(Path directory) throws IOException {
		try (Stream<Path> stream = Files.walk(directory)) {
			return stream
					.filter(Files::isRegularFile)
					.map(directory::relativize)
					.map(Path::toString)
					.map(ShaderPackScanner::mapEntry)
					.anyMatch(ShaderPackScanner::isShaderResource);
		}
	}

	private static boolean archiveContainsSupportedShaders(Path archive) throws IOException {
		try (ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(archive))) {
			java.util.zip.ZipEntry entry;
			while ((entry = zipInputStream.getNextEntry()) != null) {
				if (!entry.isDirectory() && isShaderResource(mapEntry(entry.getName()))) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean isShaderResource(Path path) {
		return path != null && path.startsWith(Path.of("assets", "sulkan", "shaders"));
	}

	private static String normalizeEntryName(String entryName) {
		String normalized = entryName.replace('\\', '/');
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		return normalized;
	}

	private static String trimLeadingContainerDirectory(String entryName) {
		int slashIndex = entryName.indexOf('/');
		if (slashIndex < 0) {
			return entryName;
		}

		String nestedEntry = entryName.substring(slashIndex + 1);
		if (nestedEntry.startsWith("assets/") || nestedEntry.startsWith("shaders/") || nestedEntry.equals("pack.png")) {
			return nestedEntry;
		}
		return entryName;
	}

	public record AvailableShaderPack(String id, String displayName, Path path, boolean directory) {
	}
}
