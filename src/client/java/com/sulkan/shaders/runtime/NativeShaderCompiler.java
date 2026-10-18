package com.sulkan.shaders.runtime;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import com.mojang.renderpearl.util.ShaderCompileException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

public final class NativeShaderCompiler implements AutoCloseable {
   private static final int VULKAN_1_2 = 4202496;
   private static final String ENTITY_GEOMETRY_SHADER = "entity_shadow_";
   private static final String SODIUM_TERRAIN_SHADER = "sodium_terrain_";
   private static final AtomicLong ASSEMBLY_SEQUENCE = new AtomicLong();
   private final Path assemblyDirectory = qaAssemblyDirectory();
   private final long compiler = Shaderc.shaderc_compiler_initialize();
   private final long options;
   private boolean closed;

   public NativeShaderCompiler() {
      if (this.compiler == 0L) {
         throw new IllegalStateException("Unable to initialize Sulkan's native shader compiler");
      }

      this.options = Shaderc.shaderc_compile_options_initialize();
      if (this.options == 0L) {
         Shaderc.shaderc_compiler_release(this.compiler);
         throw new IllegalStateException("Unable to allocate Sulkan's shader compiler options");
      }

      Shaderc.shaderc_compile_options_set_target_env(this.options, 0, 4202496);
      Shaderc.shaderc_compile_options_set_auto_bind_uniforms(this.options, true);
      Shaderc.shaderc_compile_options_set_auto_map_locations(this.options, true);
      Shaderc.shaderc_compile_options_set_generate_debug_info(this.options);
      Shaderc.shaderc_compile_options_set_preserve_bindings(this.options, true);
      Shaderc.shaderc_compile_options_set_optimization_level(this.options, 2);
   }

   public SPIRVModule compile(String name, String source, ShaderType type) throws ShaderCompileException {
      if (this.closed) {
         throw new IllegalStateException("Shader compiler is closed");
      }

      int optimizationLevel = requiresStableGeometryInterface(name) ? 0 : 2;
      Shaderc.shaderc_compile_options_set_optimization_level(this.options, optimizationLevel);
      int kind = type == ShaderType.FRAGMENT ? 1 : 0;
      long result = compileSpirv(name, source, kind);
      if (result == 0L) {
         throw new ShaderCompileException("Shader compiler could not allocate a result for " + name);
      }

      try {
         int status = Shaderc.shaderc_result_get_compilation_status(result);
         if (status != 0) {
            throw new ShaderCompileException(Shaderc.shaderc_result_get_error_message(result));
         }

         ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
         if (spirv != null && spirv.remaining() != 0) {
            ByteBuffer copy = MemoryUtil.memCalloc(spirv.remaining());
            MemoryUtil.memCopy(spirv, copy);

            try {
               return new SPIRVModule(copy, type);
            } catch (Throwable failure) {
               MemoryUtil.memFree(copy);
               throw failure;
            }
         } else {
            throw new ShaderCompileException("Shader compiler produced no SPIR-V for " + name);
         }
      } finally {
         Shaderc.shaderc_result_release(result);
      }
   }

   private static boolean requiresStableGeometryInterface(String name) {
      return name.contains("entity_shadow_") || name.contains("sodium_terrain_") || name.contains("native_geometry");
   }

   public NativeShaderCompiler.ComputeModule compileCompute(String name, String source) throws ShaderCompileException {
      if (this.closed) {
         throw new IllegalStateException("Shader compiler is closed");
      }

      Shaderc.shaderc_compile_options_set_optimization_level(this.options, 2);
      long result = compileSpirv(name, source, 2);
      if (result == 0L) {
         throw new ShaderCompileException("Could not allocate compute compilation result");
      }

      try {
         if (Shaderc.shaderc_result_get_compilation_status(result) != 0) {
            throw new ShaderCompileException(Shaderc.shaderc_result_get_error_message(result));
         } else {
            ByteBuffer sourceBytes = Shaderc.shaderc_result_get_bytes(result);
            if (sourceBytes != null && sourceBytes.hasRemaining()) {
               ByteBuffer copy = MemoryUtil.memAlloc(sourceBytes.remaining());
               try {
                  copy.put(sourceBytes.duplicate()).flip();
                  if (this.assemblyDirectory != null) dumpComputeAssembly(name, source, copy);
                  return new NativeShaderCompiler.ComputeModule(copy);
               } catch (Throwable failure) {
                  MemoryUtil.memFree(copy);
                  throw failure;
               }
            } else {
               throw new ShaderCompileException("Empty compute module");
            }
         }
      } finally {
         Shaderc.shaderc_result_release(result);
      }
   }

   private long compileSpirv(String name, String source, int kind) {
      return compileNative(name, source, kind, false);
   }

   private long compileNative(String name, String source, int kind, boolean assembly) {
      // The CharSequence overload encodes the entire expanded shader on LWJGL's
      // small per-thread stack. Source length must not exhaust that stack.
      ByteBuffer sourceUtf8 = null, nameUtf8 = null, entryUtf8 = null;
      try {
         sourceUtf8 = MemoryUtil.memUTF8(source, false);
         nameUtf8 = MemoryUtil.memUTF8(name);
         entryUtf8 = MemoryUtil.memUTF8("main");
         return assembly
            ? Shaderc.shaderc_compile_into_spv_assembly(this.compiler, sourceUtf8, kind, nameUtf8, entryUtf8, this.options)
            : Shaderc.shaderc_compile_into_spv(this.compiler, sourceUtf8, kind, nameUtf8, entryUtf8, this.options);
      } finally {
         if (entryUtf8 != null) MemoryUtil.memFree(entryUtf8);
         if (nameUtf8 != null) MemoryUtil.memFree(nameUtf8);
         if (sourceUtf8 != null) MemoryUtil.memFree(sourceUtf8);
      }
   }

   private static Path qaAssemblyDirectory() {
      String directory = System.getProperty("sulkan.qaShaderAssemblyDirectory");
      if (directory == null || directory.isBlank()) return null;
      if (!Boolean.getBoolean("sulkan.qaReadback")) {
         throw new IllegalStateException("Shader assembly dumping requires sulkan.qaReadback=true");
      }
      return Path.of(directory).toAbsolutePath().normalize();
   }

   /** Second, opt-in startup compilation only; the previously compiled binary is returned untouched. */
   private void dumpComputeAssembly(String name, String source, ByteBuffer binary) throws ShaderCompileException {
      long assembly = compileNative(name, source, 2, true);
      if (assembly == 0L) throw new ShaderCompileException("Could not allocate QA assembly result for " + name);
      try {
         if (Shaderc.shaderc_result_get_compilation_status(assembly) != 0) {
            throw new ShaderCompileException("QA assembly for " + name + ": " + Shaderc.shaderc_result_get_error_message(assembly));
         }
         ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(assembly);
         if (bytes == null || !bytes.hasRemaining()) throw new ShaderCompileException("Empty QA assembly for " + name);
         String text = StandardCharsets.UTF_8.decode(bytes.duplicate()).toString();
         // shaderc textual results may include their terminating NUL.
         while (text.endsWith("\0")) text = text.substring(0, text.length() - 1);
         String safeName = name.replaceAll("[^A-Za-z0-9._-]", "_");
         if (safeName.isEmpty()) safeName = "compute";
         if (safeName.length() > 96) safeName = safeName.substring(0, 96);
         String stem = String.format(java.util.Locale.ROOT, "%06d-%s", ASSEMBLY_SEQUENCE.incrementAndGet(), safeName);
         byte[] originalBinary = new byte[binary.remaining()];
         binary.duplicate().get(originalBinary);
         Files.createDirectories(this.assemblyDirectory);
         // Do not overwrite artifacts from another launch accidentally reusing a directory.
         Files.write(this.assemblyDirectory.resolve(stem + ".spv"), originalBinary, StandardOpenOption.CREATE_NEW);
         Files.writeString(this.assemblyDirectory.resolve(stem + ".spvasm"), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
         var metadata = new LinkedHashMap<String, Object>();
         metadata.put("passLabel", name); metadata.put("kind", "compute");
         metadata.put("profileLabel", name.contains("/") ? name.substring(name.indexOf('/') + 1) : "normal");
         metadata.put("optimizationLevel", 2); metadata.put("target", "Vulkan 1.2");
         metadata.put("sourceSha256", sha256(source.getBytes(StandardCharsets.UTF_8)));
         metadata.put("binarySha256", sha256(originalBinary));
         metadata.put("assemblySha256", sha256(text.getBytes(StandardCharsets.UTF_8)));
         metadata.put("scope", "Opt-in separate shaderc assembly compilation with the same compute source/options. Original production binary retained unchanged. Includes debug information: raw hashes/instruction counts are not GPU performance or machine-code measurements. Compile/dump I/O belongs outside timed profiling windows.");
         Files.writeString(this.assemblyDirectory.resolve(stem + ".json"),
            new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(metadata), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      } catch (ShaderCompileException failure) {
         throw failure;
      } catch (Exception failure) {
         throw new ShaderCompileException("QA assembly dump failed for " + name + ": " + failure);
      } finally {
         Shaderc.shaderc_result_release(assembly);
      }
   }

   private static String sha256(byte[] bytes) throws java.security.NoSuchAlgorithmException {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
   }

   @Override
   public void close() {
      if (!this.closed) {
         this.closed = true;
         Shaderc.shaderc_compile_options_release(this.options);
         Shaderc.shaderc_compiler_release(this.compiler);
      }
   }

   public static final class ComputeModule implements AutoCloseable {
      private ByteBuffer code;

      private ComputeModule(ByteBuffer code) {
         this.code = code;
      }

      public ByteBuffer code() {
         if (this.code == null) {
            throw new IllegalStateException("Module is closed");
         } else {
            return this.code;
         }
      }

      @Override
      public void close() {
         if (this.code != null) {
            MemoryUtil.memFree(this.code);
            this.code = null;
         }
      }
   }
}
