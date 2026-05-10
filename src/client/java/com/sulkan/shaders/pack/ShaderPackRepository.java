package com.sulkan.shaders.pack;

import com.mojang.logging.LogUtils;
import com.sulkan.shaders.config.ShaderConfig;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;

public final class ShaderPackRepository {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ACTIVE_PACK_DIRECTORY_NAME = "sulkan_shaderpack_active";
	private static final String ACTIVE_PACK_ID = "file/" + ACTIVE_PACK_DIRECTORY_NAME;

	private ShaderPackRepository() {
	}

	public static Path activePackDirectory(Path gameDirectory) {
		return gameDirectory.resolve("resourcepacks").resolve(ACTIVE_PACK_DIRECTORY_NAME);
	}

	public static boolean sync(Minecraft minecraft, ShaderConfig config, ShaderPackScanner.AvailableShaderPack selectedPack, boolean forceRebuild) {
		Path activePackDirectory = activePackDirectory(minecraft.gameDirectory.toPath());
		List<String> selectedWithoutGenerated = withoutPackId(minecraft.options.resourcePacks, ACTIVE_PACK_ID);
		List<String> incompatibleWithoutGenerated = withoutPackId(minecraft.options.incompatibleResourcePacks, ACTIVE_PACK_ID);
		boolean useExternalPack = config.enabled() && !ShaderConfig.BUILTIN_PACK_ID.equals(config.selectedPackId()) && selectedPack != null;
		boolean changed = false;

		try {
			if (!useExternalPack) {
				if (Files.exists(activePackDirectory)) {
					deleteDirectory(activePackDirectory);
					changed = true;
				}
				changed |= applyPackLists(minecraft.options, selectedWithoutGenerated, incompatibleWithoutGenerated);
				changed |= setRepositorySelection(minecraft, selectedWithoutGenerated);
				return changed;
			}

			if (forceRebuild || !Files.isDirectory(activePackDirectory) || !isPackMetadataCurrent(activePackDirectory)) {
				rebuildGeneratedPack(selectedPack, activePackDirectory);
				changed = true;
			}

			List<String> desiredSelection = new ArrayList<>();
			desiredSelection.add(ACTIVE_PACK_ID);
			desiredSelection.addAll(selectedWithoutGenerated);
			changed |= applyPackLists(minecraft.options, desiredSelection, incompatibleWithoutGenerated);
			changed |= setRepositorySelection(minecraft, desiredSelection);
			return changed;
		} catch (IOException exception) {
			LOGGER.error("Failed to sync Sulkan generated shader pack at {}", activePackDirectory, exception);
			return false;
		}
	}

	private static void rebuildGeneratedPack(ShaderPackScanner.AvailableShaderPack selectedPack, Path activePackDirectory) throws IOException {
		deleteDirectory(activePackDirectory);
		Files.createDirectories(activePackDirectory);

		boolean extractedShaders = selectedPack.directory()
				? extractDirectory(selectedPack.path(), activePackDirectory)
				: extractArchive(selectedPack.path(), activePackDirectory);
		if (!extractedShaders) {
			throw new IOException("Shader pack does not contain assets/sulkan/shaders: " + selectedPack.path());
		}
		writePackMeta(activePackDirectory, selectedPack.displayName());
	}

	private static boolean extractDirectory(Path sourceDirectory, Path targetDirectory) throws IOException {
		boolean extractedShaders = false;
		try (Stream<Path> stream = Files.walk(sourceDirectory)) {
			for (Path sourceFile : stream.filter(Files::isRegularFile).toList()) {
				Path mappedTarget = ShaderPackScanner.mapEntry(sourceDirectory.relativize(sourceFile).toString());
				if (mappedTarget == null) {
					continue;
				}
				Path targetPath = targetDirectory.resolve(mappedTarget).normalize();
				if (!targetPath.startsWith(targetDirectory)) {
					continue;
				}
				Files.createDirectories(targetPath.getParent());
				Files.copy(sourceFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
				extractedShaders |= mappedTarget.startsWith(Path.of("assets", "sulkan", "shaders"));
			}
		}
		return extractedShaders;
	}

	private static boolean extractArchive(Path sourceArchive, Path targetDirectory) throws IOException {
		boolean extractedShaders = false;
		try (ZipInputStream zipInputStream = new ZipInputStream(Files.newInputStream(sourceArchive))) {
			java.util.zip.ZipEntry entry;
			while ((entry = zipInputStream.getNextEntry()) != null) {
				if (entry.isDirectory()) {
					continue;
				}
				Path mappedTarget = ShaderPackScanner.mapEntry(entry.getName());
				if (mappedTarget == null) {
					continue;
				}
				Path targetPath = targetDirectory.resolve(mappedTarget).normalize();
				if (!targetPath.startsWith(targetDirectory)) {
					continue;
				}
				Files.createDirectories(targetPath.getParent());
				Files.copy(zipInputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
				extractedShaders |= mappedTarget.startsWith(Path.of("assets", "sulkan", "shaders"));
			}
		}
		return extractedShaders;
	}

	private static boolean isPackMetadataCurrent(Path packDirectory) {
		return Files.isRegularFile(packDirectory.resolve("pack.mcmeta"));
	}

	private static void writePackMeta(Path packDirectory, String packName) throws IOException {
		Files.createDirectories(packDirectory);
		try (Writer writer = Files.newBufferedWriter(packDirectory.resolve("pack.mcmeta"))) {
			int packVersion = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES).major();
			writer.write("""
					{
					  "pack": {
					    "pack_format": %d,
					    "description": "Sulkan shader pack: %s"
					  }
					}
					""".formatted(packVersion, packName.replace("\"", "'")));
		}
	}

	private static boolean applyPackLists(Options options, List<String> selected, List<String> incompatible) {
		boolean changed = false;
		if (!options.resourcePacks.equals(selected)) {
			options.resourcePacks.clear();
			options.resourcePacks.addAll(selected);
			changed = true;
		}
		if (!options.incompatibleResourcePacks.equals(incompatible)) {
			options.incompatibleResourcePacks.clear();
			options.incompatibleResourcePacks.addAll(incompatible);
			changed = true;
		}
		return changed;
	}

	private static boolean setRepositorySelection(Minecraft minecraft, List<String> selectedPackIds) {
		List<String> currentSelectedIds = List.copyOf(minecraft.getResourcePackRepository().getSelectedIds());
		if (currentSelectedIds.equals(selectedPackIds)) {
			return false;
		}
		minecraft.getResourcePackRepository().setSelected(selectedPackIds);
		return true;
	}

	private static List<String> withoutPackId(List<String> packIds, String packId) {
		List<String> result = new ArrayList<>();
		for (String currentId : packIds) {
			if (!packId.equals(currentId)) {
				result.add(currentId);
			}
		}
		return result;
	}

	private static void deleteDirectory(Path directory) throws IOException {
		if (!Files.exists(directory)) {
			return;
		}

		try (Stream<Path> stream = Files.walk(directory)) {
			for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}
}
