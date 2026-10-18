package com.sulkan.shaders.pack;

import com.sulkan.shaders.graph.NativePackRuntime;
import com.sulkan.shaders.graph.PackFiles;
import com.sulkan.shaders.graph.PackGraph;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;

public final class ShaderPackScanner {
   private static final String SHADER_DIRECTORY_NAME = "shaders";

   private ShaderPackScanner() {
   }

   public static Path shaderPackDirectory() {
      return FabricLoader.getInstance().getGameDir().resolve("shaders");
   }

   public static void ensureShaderPackDirectory() {
      try {
         Files.createDirectories(shaderPackDirectory());
      } catch (IOException exception) {
         throw new IllegalStateException("Failed to create Sulkan shader pack directory", exception);
      }
   }

   public static List<ShaderPackScanner.AvailableShaderPack> listAvailablePacks() {
      return scan().supportedPacks();
   }

   public static ShaderPackScanner.ScanResult scan() {
      ensureShaderPackDirectory();
      return scanDirectory(shaderPackDirectory());
   }

   static ShaderPackScanner.ScanResult scanDirectory(Path directory) {
      List<ShaderPackScanner.AvailableShaderPack> supported = new ArrayList<>();
      List<ShaderPackScanner.UnsupportedShaderPack> unsupported = new ArrayList<>();

      try (Stream<Path> stream = Files.list(directory)) {
         stream.sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
            .forEach(path -> classify(path, supported, unsupported));
      } catch (IOException exception) {
         unsupported.add(new ShaderPackScanner.UnsupportedShaderPack(directory.toString(), "unreadable-folder", directory));
      }

      return new ShaderPackScanner.ScanResult(List.copyOf(supported), List.copyOf(unsupported));
   }

   public static boolean isKnownPackId(String packId, List<ShaderPackScanner.AvailableShaderPack> packs) {
      if ("__builtin__".equals(packId)) {
         return true;
      }

      for (ShaderPackScanner.AvailableShaderPack pack : packs) {
         if (pack.id().equals(packId)) {
            return true;
         }
      }

      return false;
   }

   private static void classify(Path path, List<ShaderPackScanner.AvailableShaderPack> supported, List<ShaderPackScanner.UnsupportedShaderPack> unsupported) {
      String fileName = path.getFileName().toString();
      if (!isCandidatePack(path)) {
         unsupported.add(new ShaderPackScanner.UnsupportedShaderPack(fileName, "not-a-shader-pack", path));
      } else {
         if (containsSupportedShaders(path)) {
            supported.add(toAvailablePack(path));
         } else {
            unsupported.add(new ShaderPackScanner.UnsupportedShaderPack(fileName, "not-sulkan-compatible", path));
         }
      }
   }

   private static boolean isCandidatePack(Path path) {
      if (Files.isDirectory(path)) {
         return true;
      }

      String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
      return Files.isRegularFile(path) && fileName.endsWith(".zip");
   }

   private static ShaderPackScanner.AvailableShaderPack toAvailablePack(Path path) {
      String fileName = path.getFileName().toString();
      String displayName = fileName.endsWith(".zip") ? fileName.substring(0, fileName.length() - 4) : fileName;
      return new ShaderPackScanner.AvailableShaderPack(fileName, displayName, path, Files.isDirectory(path));
   }

   private static boolean containsSupportedShaders(Path path) {
      try {
         if (NativePackRuntime.isNative(path)) {
            PackFiles files = PackFiles.read(path);
            PackGraph.parse(files.text("sulkan.json"));
            return true;
         } else {
            return false;
         }
      } catch (IOException | IllegalArgumentException exception) {
         com.mojang.logging.LogUtils.getLogger().warn("Ignoring invalid Sulkan pack {}: {}",path.getFileName(),exception.getMessage());
         return false;
      }
   }

   public record AvailableShaderPack(String id, String displayName, Path path, boolean directory) {
   }

   public record ScanResult(List<ShaderPackScanner.AvailableShaderPack> supportedPacks, List<ShaderPackScanner.UnsupportedShaderPack> unsupportedPacks) {
   }

   public record UnsupportedShaderPack(String displayName, String reason, Path path) {
   }
}
