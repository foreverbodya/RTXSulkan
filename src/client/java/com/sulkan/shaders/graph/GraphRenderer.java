package com.sulkan.shaders.graph;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.commands.RenderPass.RenderArea;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor.Attachment;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout.Builder;
import com.mojang.renderpearl.api.pipeline.RenderPipeline.Snippet;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.util.UncheckedAutoCloseable;
import com.sulkan.shaders.mixin.GraphDeviceAccessor;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.sulkan.shaders.platform.vulkan.RayTracingFeatures;
import com.sulkan.shaders.render.raytracing.TerrainRayScene;
import com.sulkan.shaders.render.raytracing.TerrainGeometryCache;
import com.sulkan.shaders.render.shadow.HeldLightShadowRenderer;
import com.sulkan.shaders.render.shadow.ShadowService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

public final class GraphRenderer implements AutoCloseable {
   private static long generation;
   private static final String VERTEX = "#version 450\nlayout(location=0) out vec2 texCoord;\nvoid main() {\n\ttexCoord = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);\n\tgl_Position = vec4(texCoord * 2.0 - 1.0, 0.0, 1.0);\n}\n";
   private static final String PRESENT = "#version 450\nlayout(location=0) in vec2 texCoord;\nlayout(location=0) out vec4 color;\nuniform sampler2D Source;\nvoid main() { color = texture(Source, texCoord); }\n";
   private final PackGraph graph;
   private final Map<String,byte[]> diagnosticTextureSources=new HashMap<>();
   public byte[] diagnosticTextureSource(String passName,String slot) {
      String input=diagnosticPassInputs(passName).get(slot);
      if(input==null || !input.startsWith("$texture/"))throw new IllegalStateException("No diagnostic texture binding "+passName+"/"+slot);
      byte[] source=this.diagnosticTextureSources.get(input.substring(9));
      if(source==null)throw new IllegalStateException("No captured diagnostic texture source "+input);
      return source.clone();
   }
   public Map<String,Double> diagnosticOptions() { return Map.copyOf(graph.options()); }
   public Map<String,String> diagnosticPassInputs(String name) {
      if(!Boolean.getBoolean("sulkan.qaReadback"))throw new IllegalStateException("Diagnostic pass inputs are disabled");
      return Map.copyOf(this.schedule.stream().filter(pass->pass.name().equals(name)).findFirst()
          .orElseThrow(()->new IllegalStateException("No scheduled diagnostic pass "+name)).reads());
   }
   private final MaterialTable materials;
   private final List<PackGraph.Pass> schedule;
   private final Map<PackGraph.Pass, RenderPipeline> pipelines = new LinkedHashMap<>();
   private final Map<PackGraph.Pass, ComputeProgram> computePipelines = new LinkedHashMap<>();
   private final List<RenderPipeline> ownedPipelines = new ArrayList<>();
   private final Map<String, GraphRenderer.Image[]> images = new LinkedHashMap<>();
   private final GraphFrame frame = new GraphFrame();
   private HeldLightShadowRenderer heldShadows;
   private final GpuSampler sampler;
   private GpuSampler linearSampler;
   private final RenderPipeline present;
   private int width;
   private int height;
   private int parity;
   private boolean closed;
   private long renderedFrames;
   private GpuPassTimings gpuTimings;

   public void diagnosticBeginGpuTimings(String phase,java.nio.file.Path directory) {
      if(!Boolean.getBoolean("sulkan.qaGpuTimings"))return;
      if(this.gpuTimings!=null && !this.gpuTimings.ready())throw new IllegalStateException("Previous GPU timing phase incomplete");
      var names=new ArrayList<>(this.schedule.stream().map(PackGraph.Pass::name).toList());
      if(this.reconstruction!=null)names.add("native.dlss");
      names.add("world.total");names.add("composition.present");
      var device=(VulkanDevice)((GraphDeviceAccessor)RenderSystem.getDevice()).sulkan$backend();
      this.gpuTimings=new GpuPassTimings(device,phase,directory,names,Map.of("pack",this.graph.name(),
              "options",this.graph.options(),"inputWidth",this.width,"inputHeight",this.height,
              "geometry",TerrainGeometryCache.statistics(),"rayScene",this.diagnosticRayScene()));
   }

   public boolean diagnosticGpuTimingsReady() {
      return this.gpuTimings==null || this.gpuTimings.ready();
   }
   private GpuTexture diagnosticDisplay;
   private final Map<Integer,RenderPipeline> diagnosticDisplayPipelines=new HashMap<>();
   private ScenePrograms scenePrograms;
   private GpuBufferSlice windUniforms;
   private final ByteBuffer windBytes = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
   private static final long WIND_START = System.nanoTime();
   private GpuBufferSlice sceneUniforms;
   private final Map<String, GraphRenderer.Image> customTextures = new LinkedHashMap<>();
   private final Map<String, GpuSampler> textureSamplers = new LinkedHashMap<>();
   private final Map<String, Integer> mipFilters = new HashMap<>();
   private long textureBytes;
   private GraphBuffers buffers;
   private GraphSceneCapture sceneCapture;
   private final Set<RenderPipeline> heldPostPipelines = new HashSet<>();
   private final Set<RenderPipeline> shadowPostPipelines = new HashSet<>();
   private TerrainRayScene rayScene;
   private Object rayWorld;
   private VulkanDevice rayDevice;
   private WorldReconstruction reconstruction;
   public boolean reconstructsWorld() { return this.reconstruction!=null; }
   private GpuPassTimings.Measurement worldTiming;
   public RenderTarget beginWorldFrame(RenderTarget output) {
      if(this.gpuTimings!=null)worldTiming=this.gpuTimings.start(((VulkanDevice)((GraphDeviceAccessor)RenderSystem.getDevice()).sulkan$backend()).createCommandEncoder(),"world.total");
      if(this.reconstruction==null) return output;
      this.reconstruction.prepare(output);
      this.reconstruction.beginFrame();
      return this.reconstruction.world();
   }
   public long reconstructionEvaluations() { return this.reconstruction==null ? 0 : this.reconstruction.evaluations(); }
   public Map<String,Object> diagnosticReconstruction() {
      if(!Boolean.getBoolean("sulkan.qaReadback") || this.reconstruction==null) throw new IllegalStateException("No diagnostic reconstruction");
      var result=new LinkedHashMap<String,Object>(this.reconstruction.diagnostic());
      result.put("colorResource",this.graph.reconstruction().color());
      result.put("depthResource",this.graph.reconstruction().depth());
      result.put("motionResource",this.graph.reconstruction().motion());
      result.put("exposureResource",this.graph.reconstruction().exposure());
      result.put("displayToneMapper",this.graph.options().getOrDefault("DISPLAY_TONEMAPPER",0.0).intValue());
      if(this.diagnosticDisplay!=null)result.put("displayFormat",this.diagnosticDisplay.getFormat().name());
      return Map.copyOf(result);
   }
   private long rayDispatches;

   public long rayDispatches() { return this.rayDispatches; }

   public ByteBuffer diagnosticFrame() { return this.frame.diagnosticSnapshot(); }
   public TerrainRayScene.Statistics diagnosticRayScene() {
      if(!Boolean.getBoolean("sulkan.qaReadback") || this.rayScene==null) throw new IllegalStateException("No diagnostic ray scene");
      return this.rayScene.statistics();
   }

   /** Opt-in, asynchronous diagnostic copy; normal frames do not map or wait. */
   public java.util.concurrent.CompletableFuture<ByteBuffer> diagnosticReadback(String resource) {
      if (!Boolean.getBoolean("sulkan.qaReadback")) throw new IllegalStateException("Diagnostic readback is disabled");
      GraphRenderer.Image[] pair=this.images.get(PackGraph.current(resource));
      boolean reconstructed="$reconstructed".equals(resource) && this.reconstruction!=null;
      boolean displayed="$display".equals(resource) && this.diagnosticDisplay!=null;
      if ((!reconstructed && !displayed && pair==null) || this.renderedFrames==0) throw new IllegalStateException("No rendered diagnostic resource: "+resource);
      // parity already points at the next frame after render() returns.
      GpuTexture texture = displayed ? this.diagnosticDisplay : reconstructed ? this.reconstruction.output().texture() : pair[pair.length==1 ? 0 : PackGraph.previous(resource) ? this.parity : this.parity^1].texture;
      return readbackTexture(texture,resource);
   }
   private static java.util.concurrent.CompletableFuture<ByteBuffer> readbackTexture(GpuTexture texture,String resource) {
      long size = (long) texture.getWidth(0) * texture.getHeight(0) * texture.getFormat().blockSize();
      var readback = RenderSystem.getDevice().createBuffer(() -> "Sulkan diagnostic " + resource, 9, size);
      var result = new java.util.concurrent.CompletableFuture<ByteBuffer>();
      try {
         RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, readback, 0, () -> {
            try (var mapped = readback.map(true, false)) {
               ByteBuffer data = ByteBuffer.allocate(Math.toIntExact(size)).order(ByteOrder.nativeOrder());
               data.put(mapped.data()).flip();
               result.complete(data);
            } catch (Throwable failure) { result.completeExceptionally(failure); }
            finally { readback.close(); }
         }, 0);
      } catch (Throwable failure) {
         readback.close();
         result.completeExceptionally(failure);
      }
      return result;
   }

   /** Full-resolution display variants of one completed HDR/exposure frame; no world or NGX reset. */
   public Map<Integer,java.util.concurrent.CompletableFuture<ByteBuffer>> diagnosticDisplayVariants() {
      if(!Boolean.getBoolean("sulkan.qaReadback") || !Boolean.getBoolean("sulkan.qaToneMapComparison")
          || this.closed || this.renderedFrames==0 || this.reconstruction==null || this.diagnosticDisplayPipelines.size()!=4)
         throw new IllegalStateException("Display comparison is unavailable");
      var device=RenderSystem.getDevice();var source=this.reconstruction.output();
      int width=source.texture().getWidth(0),height=source.texture().getHeight(0);
      var result=new LinkedHashMap<Integer,java.util.concurrent.CompletableFuture<ByteBuffer>>();
      for(int mode:List.of(0,1,2,3)) {
         var texture=device.createTexture("Sulkan display comparison "+mode,10,GpuFormat.RGBA8_UNORM,width,height,1,1);
         Image target;
         try {target=new Image(texture,device.createTextureView(texture));}
         catch(Throwable problem){texture.close();throw problem;}
         try {
            var encoder=device.createCommandEncoder();
            try(var pass=encoder.createRenderPass(()->"Sulkan diagnostic display "+mode,target.view,Optional.empty())) {
               pass.setPipeline(RenderSystem.getCompiledPipeline(this.diagnosticDisplayPipelines.get(mode)));
               RenderSystem.bindDefaultUniforms(pass);
               pass.setUniform("Source",source,this.sampler);
               for(var input:this.graph.reconstruction().displayReads().entrySet()) {
                  // render() has advanced parity; bind the completed exposure, never its previous frame.
                  var pair=this.images.get(PackGraph.current(input.getValue()));
                  pass.setUniform(input.getKey(),pair[pair.length==1?0:this.parity^1].view,this.sampler);
               }
               pass.draw(3,1,0,0);
            }
            result.put(mode,readbackTexture(texture,"display mode "+mode).whenComplete((data,error)->
                Minecraft.getInstance().execute(()->RenderSystem.queueFencedTask(target::close))));
         } catch(Throwable problem) {
            RenderSystem.queueFencedTask(target::close);throw problem;
         }
      }
      return Map.copyOf(result);
   }

   public MaterialTable materials() {
      return this.materials;
   }

   public HeldLightShadowRenderer heldShadows() {
      return this.heldShadows;
   }

   public long renderedFrames() {
      return this.renderedFrames;
   }

   public long sceneReplacementCount() {
      return this.scenePrograms.replacementCount();
   }

   public void invalidateHistory() {
      this.frame.reset();
   }

   public long terrainCaptures() {
      return this.sceneCapture.captures();
   }

   public void captureTerrain(RenderTarget main) {
      this.sceneCapture.capture(main);
   }

   public void captureWorldDepth(RenderTarget main) {
      this.sceneCapture.captureWorldDepth(main);
   }

   public GraphRenderer(PackGraph graph, PackFiles files, RenderTarget main) throws IOException {
      this.graph = graph;
      this.schedule = graph.schedule();
      this.frame.heldLighting = graph.options().getOrDefault("HELD_LIGHTING", 0.0) > 0.0;
      this.materials = MaterialTable.compile(graph.materials());
      this.materials.vegetationWind = graph.options().containsKey("VEGETATION_WIND");
      GpuDevice device = RenderSystem.getDevice();
      this.sampler = device.createSampler(
         AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty()
      );

      try {
         this.linearSampler = device.createSampler(
            AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty()
         );
         if (graph.sceneTargets().size() + 1 > device.getDeviceInfo().limits().maxColorAttachments()) {
            throw new IOException("GPU cannot provide the pack's scene attachments");
         }

         this.sceneCapture = new GraphSceneCapture(graph, main);
         if (this.frame.heldLighting) {
            this.heldShadows = new HeldLightShadowRenderer(this.frame.heldLight);
         }

         this.scenePrograms = new ScenePrograms(graph, files);
         if (this.schedule.stream().anyMatch(PackGraph.Pass::rayScene)) {
            var backend = (VulkanDevice) ((GraphDeviceAccessor) device).sulkan$backend();
            if (!RayTracingFeatures.enabledFor(backend.vkDevice())) {
               throw new IOException("This pack requires enabled hardware ray queries");
            }
            this.rayScene = new TerrainRayScene(backend.vkDevice(), this.schedule.stream().anyMatch(PackGraph.Pass::rayAttributes));
            this.rayDevice = backend;
            this.rayScene.enqueue(TerrainGeometryCache.replay());
         }

         for (PackGraph.Pass pass : this.schedule) {
            if (pass.isCompute()) {
               this.computePipelines.put(pass, new ComputeProgram(graph, pass, files));
            } else {
               if (pass.writes().size() > device.getDeviceInfo().limits().maxColorAttachments()) {
                  throw new IOException("Device cannot provide " + pass.writes().size() + " color attachments for " + pass.name());
               }

               List<GpuFormat> formats = pass.writes().stream().map(n -> graph.resources().get(n).format()).toList();
               this.pipelines.put(pass, this.pipeline(pass.name(), files.shader(pass.fragment(), graph.options()), pass.reads().keySet(), formats, true));
            }
         }

         var displaySamplers=new java.util.LinkedHashSet<>(Set.of("Source"));
         if(graph.reconstruction()!=null)displaySamplers.addAll(graph.reconstruction().displayReads().keySet());
         this.present = this.pipeline(
            "present",
            graph.reconstruction()==null ? PRESENT : files.shader(graph.reconstruction().display(),graph.options()),
            displaySamplers,
            List.of(main.getColorTexture().getFormat()),
            false
         );
         if(Boolean.getBoolean("sulkan.qaToneMapComparison")) {
            if(!Boolean.getBoolean("sulkan.qaReadback") || graph.reconstruction()==null
                || !graph.reconstruction().displayReads().containsKey("DisplayExposure") || !graph.options().containsKey("DISPLAY_TONEMAPPER"))
               throw new IOException("Display comparison requires reconstructed HDR, auto exposure and a selectable display curve");
            for(int mode:List.of(0,1,2,3)) {
               var options=new java.util.TreeMap<>(graph.options());options.put("DISPLAY_TONEMAPPER",(double)mode);
               this.diagnosticDisplayPipelines.put(mode,this.pipeline("diagnostic-display-"+mode,
                   files.shader(graph.reconstruction().display(),options),displaySamplers,List.of(GpuFormat.RGBA8_UNORM),false));
            }
         }
         this.loadTextures(files);
         if(graph.reconstruction()!=null) this.reconstruction=new WorldReconstruction((VulkanDevice)((GraphDeviceAccessor)device).sulkan$backend(),main);
         RenderTarget initial=this.reconstruction==null ? main : this.reconstruction.world();
         this.allocate(initial.width, initial.height);
         this.buffers = new GraphBuffers(graph);
      } catch (Exception failure) {
         this.close();
         throw new IOException("Cannot activate " + graph.name() + ": " + failure.getMessage(), failure);
      }
   }

   private RenderPipeline pipeline(String label, String fragment, Set<String> inputs, List<GpuFormat> formats, boolean frameUniform) throws IOException {
      String path = "native/g" + ++generation + "/" + label;
      Builder bindings = BindGroupLayout.builder();
      inputs.forEach(name -> bindings.withUniform(name, UniformType.COMBINED_IMAGE_SAMPLER));
      if (frameUniform) {
         bindings.withUniform("SulkanFrame", UniformType.UNIFORM_BUFFER);
      }

      boolean shadows = frameUniform && this.graph.shadowQuality() > 0 && fragment.contains("uniform SulkanShadowData");
      if (shadows) {
         ShadowService.layout(bindings);
      }

      boolean held = this.frame.heldLighting && fragment.contains("uniform sampler2D SulkanHeldShadow0");
      if (held) {
         HeldLightShadowRenderer.layout(bindings);
      }

      com.mojang.renderpearl.api.pipeline.RenderPipeline.Builder builder = RenderPipeline.builder(new Snippet[]{RenderPipelines.POST_PROCESSING_SNIPPET})
         .withLocation(Identifier.fromNamespaceAndPath("sulkan", path))
         .withVertexShader(Identifier.fromNamespaceAndPath("sulkan", path + "_vertex"))
         .withFragmentShader(Identifier.fromNamespaceAndPath("sulkan", path + "_fragment"))
         .withBindGroupLayout(bindings.build());

      for (int i = 0; i < formats.size(); i++) {
         builder.withColorTargetState(i, new ColorTargetState(Optional.empty(), formats.get(i), 15));
      }

      RenderPipeline pipeline = builder.build();
      this.ownedPipelines.add(pipeline);
      if (shadows) {
         this.shadowPostPipelines.add(pipeline);
      }

      if (held) {
         this.heldPostPipelines.add(pipeline);
      }

      GraphShaderSources.put(
         pipeline,
         "#version 450\nlayout(location=0) out vec2 texCoord;\nvoid main() {\n\ttexCoord = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);\n\tgl_Position = vec4(texCoord * 2.0 - 1.0, 0.0, 1.0);\n}\n",
         fragment
      );
      if (RenderSystem.getCompiledPipelineNullable(pipeline) == null) {
         throw new IOException("Shader compilation failed in pass " + label + "; see log for source diagnostics");
      } else {
         return pipeline;
      }
   }

   private void allocate(int width, int height) {
      width = Math.max(1, width);
      height = Math.max(1, height);
      if (this.width != width || this.height != height) {
         if (this.graph.allocationBytes(width, height)
               + this.textureBytes
               + (this.heldShadows == null ? 0L : 7864320L)
               + this.sceneCapture.bytes(width, height)
               + ShadowService.memoryBytes(this.graph.shadowQuality())
               + (this.reconstruction==null ? 0 : this.reconstruction.bytes())
            > this.graph.budgetBytes()) {
            throw new IllegalArgumentException("Textures and render targets exceed pack memory budget");
         }

         Map<String, GraphRenderer.Image[]> fresh = new LinkedHashMap<>();
         GraphAllocations plan = GraphAllocations.plan(this.graph);
         Set<Integer> storageSlots = new HashSet<>();
         Set<Integer> rayClearSlots = new HashSet<>();

         for (PackGraph.Pass pass : this.schedule) {
            if (pass.isCompute()) {
               for (String output : pass.writes()) {
                  storageSlots.add(plan.resourceSlots().get(output));
                  if (pass.rayScene()) rayClearSlots.add(plan.resourceSlots().get(output));
               }
            }
         }

         Map<Integer, GraphRenderer.Image[]> physical = new HashMap<>();

         try {
            GpuDevice device = RenderSystem.getDevice();

            for (String name : plan.resourceSlots().keySet()) {
               PackGraph.Resource r = this.graph.resources().get(name);
               if (r.mipmaps()) {
                  this.mipFilters.put(name, GraphMipmaps.filter(r.format()));
               }

               int slot = plan.resourceSlots().get(name);
               if (!physical.containsKey(slot)) {
                  int w = r.width(width);
                  int h = r.height(height);
                  int limit = device.getDeviceInfo().limits().maxTextureSizeForFormat(r.format());
                  if (w > limit || h > limit) {
                     throw new IllegalArgumentException("Unsupported size/format for " + name + ": " + w + "x" + h);
                  }

                  long size = r.bytes(width, height);
                  if (size > device.getDeviceInfo().limits().maxMemoryAllocationSize()) {
                     throw new IllegalArgumentException("Allocation exceeds device limit: " + name);
                  }

                  GraphRenderer.Image[] pair = new GraphRenderer.Image[r.history() ? 2 : 1];
                  fresh.put(name, pair);
                  physical.put(slot, pair);

                  for (int i = 0; i < pair.length; i++) {
                     try (StorageImageScope scope = new StorageImageScope(storageSlots.contains(slot), r.depth())) {
                        GpuTexture texture = ((GraphDeviceAccessor)device)
                           .sulkan$backend()
                           .createTexture(
                              "Sulkan " + name,
                              (r.depth() == 1 ? 8 : 0)
                                 | 4
                                 | (rayClearSlots.contains(slot) ? 1 : 0)
                                 | (!r.history() && !this.graph.sceneTargets().contains(name) && !r.mipmaps() ? 0 : 1)
                                 | (r.mipmaps() || Boolean.getBoolean("sulkan.qaReadback") ? 2 : 0),
                              r.format(),
                              w,
                              h,
                              r.depth(),
                              r.mipLevels(width, height)
                           );

                        try {
                           GpuTextureView sampled = device.createTextureView(texture);

                           try {
                              pair[i] = new GraphRenderer.Image(texture, sampled, r.mipmaps() ? device.createTextureView(texture, 0, 1) : sampled);
                           } catch (Exception failure) {
                              sampled.close();
                              throw failure;
                           }
                        } catch (Exception failure) {
                           texture.close();
                           throw failure;
                        }
                     }
                  }
               } else {
                  fresh.put(name, physical.get(slot));
               }
            }
         } catch (Exception failure) {
            destroy(fresh);
            throw failure;
         }

         try {
            this.sceneCapture.resize(width, height);
         } catch (Exception failure) {
            destroy(fresh);
            throw failure;
         }

         Map<String, GraphRenderer.Image[]> old = new LinkedHashMap<>(this.images);
         this.images.clear();
         this.images.putAll(fresh);
         if (!old.isEmpty()) {
            RenderSystem.queueFencedTask(() -> destroy(old));
         }

         this.width = width;
         this.height = height;
         this.parity = 0;
         this.frame.reset();
      }
   }

   private static boolean profileDisabled(String name) {
      if(!Boolean.getBoolean("sulkan.qaPerformanceProfile"))return false;
      return switch(System.getProperty("sulkan.qaProfileDisabled","")) {
         case "clouds" -> name.equals("cloud.view") || name.equals("cloud.environment");
         case "gi" -> name.equals("indirect.trace") || name.equals("leaf.indirect.trace");
         case "reflections" -> name.equals("ray.reflection") || name.equals("rough.reflection");
         case "water" -> name.startsWith("water.") && !name.contains("composite");
         case "shadows" -> name.equals("ray.direct");
         case "denoising" -> name.contains("prefilter") || name.contains("spatial") || name.contains("temporal");
         default -> false;
      };
   }
   public void render(RenderTarget main, CameraRenderState camera, Matrix4fc view, Object world) {
      this.render(main,main,camera,view,world);
   }
   public void render(RenderTarget main,RenderTarget destination, CameraRenderState camera, Matrix4fc view, Object world) {
      if (this.closed) {
         throw new IllegalStateException("Graph is closed");
      }

      this.allocate(main.width, main.height);
      this.sceneCapture.captureWorldDepth(main);
      CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
      boolean reset = this.frame.discontinuity(camera, world, view);
      boolean resetGlobal = this.frame.globalDiscontinuity(camera, world);
      this.buffers.beginFrame(this.parity, reset);
      if (reset) {
         for (GraphRenderer.Image[] pair : this.images.values()) {
            if (pair.length == 2) {
               for (GraphRenderer.Image image : pair) {
                  if (resetGlobal || image.texture.getWidth(0) != 1 || image.texture.getHeight(0) != 1 || image.texture.getDepthOrLayers() != 1) {
                     if (image.texture.getDepthOrLayers() > 1) {
                        ComputeProgram.clearVolume(image.texture);
                     } else {
                        encoder.clearColorTexture(image.texture, new Vector4f(0.0F));
                     }
                  }
               }
            }
         }
      }

      long rayHandle = 0;
      com.sulkan.shaders.platform.vulkan.NativeBuffer rayAttributes = null;
      if (this.rayScene != null) {
         if (this.rayWorld != world) {
            this.rayWorld = world;
            this.rayScene.enqueue(TerrainGeometryCache.replay());
         }
         if (!TerrainGeometryCache.available()) {
            throw new IllegalStateException("Ray geometry unavailable: " + TerrainGeometryCache.statistics().failure());
         }
         this.rayScene.enqueue(TerrainGeometryCache.drainChanges());
         this.rayScene.sampleEmission(this.graph.options().getOrDefault("EMISSIVE_SAMPLING",0d)>0);
         if(this.graph.cameraRelativeRays()) this.rayScene.followCamera(camera.pos.x,camera.pos.y,camera.pos.z);
         TerrainRayScene.View rayView = this.rayScene.synchronize(this.rayDevice.createCommandEncoder(), 8,this.gpuTimings);
         rayHandle = rayView.handle();
         rayAttributes = rayView.attributeData();
         this.frame.emissionSources(this.rayScene.emissionAddress(),this.rayScene.emissionTriangles());
         this.frame.rayOrigin(rayView.origin());
         this.frame.raySceneState(rayView.generation(),this.rayScene.pendingSections(),rayHandle!=0);
      }
      GpuBufferSlice uniforms = this.frame.upload(encoder, camera, view, this.width, this.height, reset);
      boolean directPresent = this.reconstruction==null && main==destination && canPresentDirectly(this.graph, this.schedule.getLast(), destination.getColorTexture().getFormat(), this.width, this.height);
      this.graph.sceneTargets().forEach(this::generateMipmaps);

      for (PackGraph.Pass node : this.schedule) {
         if (node.isCompute()) {
            if(profileDisabled(node.name())) {
               var m=this.gpuTimings==null?null:this.gpuTimings.start(this.rayDevice.createCommandEncoder(),node.name());
               node.writes().forEach(output->encoder.clearColorTexture(this.image(output).texture,new Vector4f(0)));
               if(m!=null)m.finish(true);continue;
            }
            if (node.rayScene() && rayHandle == 0) {
               // A loading/empty scene has no valid AS descriptor. Define outputs
               // explicitly until geometry is available; never trace a null handle.
               var emptyTiming=this.gpuTimings==null?null:this.gpuTimings.start(this.rayDevice.createCommandEncoder(),node.name());
               node.writes().forEach(output -> encoder.clearColorTexture(this.image(output).texture, new Vector4f(0)));
               if(emptyTiming!=null)emptyTiming.finish(true);
               continue;
            }
            this.computePipelines
               .get(node)
               .dispatch(
                  uniforms,
                  resource -> node.writes().contains(resource) ? this.image(resource).attachment : this.resolve(resource, main),
                  this::sampler,
                  this.buffers, rayHandle, rayAttributes, this.gpuTimings, this.linearSampler
               );
            if (node.rayScene()) this.rayDispatches++;
            node.writes().forEach(this::generateMipmaps);
         } else {
            com.mojang.renderpearl.api.commands.RenderPassDescriptor.Builder descriptor = RenderPassDescriptor.builder(
               () -> this.graph.name() + "/" + node.name()
            );
            GpuTexture first = this.image(node.writes().getFirst()).texture;
            descriptor.withRenderArea(new RenderArea(0, 0, first.getWidth(0), first.getHeight(0)));
            boolean outputToMain = directPresent && node.equals(this.schedule.getLast());

            for (String output : node.writes()) {
               descriptor.withColorAttachment(outputToMain ? main.getColorTextureView() : this.image(output).attachment, Optional.of(new Vector4f(0.0F)));
            }

            var rasterTiming=this.gpuTimings==null?null:this.gpuTimings.start(this.rayDevice.createCommandEncoder(),node.name());
            RenderPass pass = encoder.createRenderPass(descriptor.build());

            try {
               pass.setPipeline(RenderSystem.getCompiledPipeline(this.pipelines.get(node)));
               RenderSystem.bindDefaultUniforms(pass);
               pass.setUniform("SulkanFrame", uniforms);
               if (this.heldPostPipelines.contains(this.pipelines.get(node))) {
                  this.heldShadows.bind(pass);
               }

               if (this.shadowPostPipelines.contains(this.pipelines.get(node))) {
                  ShadowService.bindTerrain(pass);
               }

               node.reads()
                  .forEach(
                     (binding, resource) -> pass.setUniform(
                        binding, this.resolve(resource, main), node.linearReads().contains(binding) ? this.linearSampler : this.sampler(resource)
                     )
                  );
               pass.draw(3, 1, 0, 0);
            } catch (Throwable var21) {
               if (pass != null) {
                  try {
                     pass.close();
                  } catch (Throwable var19) {
                     var21.addSuppressed(var19);
                  }
               }

               throw var21;
            }

            if (pass != null) {
               pass.close();
            }

            if(rasterTiming!=null)rasterTiming.finish(true);
            node.writes().forEach(this::generateMipmaps);
         }
      }

      if(directPresent && this.gpuTimings!=null){var m=this.gpuTimings.start(this.rayDevice.createCommandEncoder(),"composition.present");if(m!=null)m.finish(true);}
      if (!directPresent) {
         if(this.reconstruction!=null) {
            var bindings=this.graph.reconstruction();
            var nativeEncoder=((VulkanDevice)((GraphDeviceAccessor)RenderSystem.getDevice()).sulkan$backend()).createCommandEncoder();
            var measurement=this.gpuTimings==null?null:this.gpuTimings.start(nativeEncoder,"native.dlss");
            boolean submitted=false;
            try {
               this.reconstruction.record(nativeEncoder,new com.sulkan.shaders.platform.vulkan.dlss.DlssReconstruction.Frame(
                    this.image(bindings.color()).view,this.image(bindings.depth()).view,
                    this.image(bindings.motion()).view,this.image(bindings.exposure()).view,
                    this.frame.jitterX(),this.frame.jitterY(),reset));
               submitted=true;
            } finally {if(measurement!=null)measurement.finish(submitted);}
         }
         var presentTiming=this.gpuTimings==null?null:this.gpuTimings.start(this.rayDevice.createCommandEncoder(),"composition.present");
         RenderPass pass = encoder.createRenderPass(() -> "Sulkan present", destination.getColorTextureView(), Optional.empty());

         try {
            pass.setPipeline(RenderSystem.getCompiledPipeline(this.present));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Source", this.reconstruction==null ? this.image(this.graph.present()).view : this.reconstruction.output(), this.sampler);
            if(this.reconstruction!=null) for(var input:this.graph.reconstruction().displayReads().entrySet())
               pass.setUniform(input.getKey(),this.image(input.getValue()).view,this.sampler);
            pass.draw(3, 1, 0, 0);
         } catch (Throwable var20) {
            if (pass != null) {
               try {
                  pass.close();
               } catch (Throwable var18) {
                  var20.addSuppressed(var18);
               }
            }

            throw var20;
         }

         if (pass != null) {
            pass.close();
         }
         if(presentTiming!=null)presentTiming.finish(true);
      }

      if(worldTiming!=null){worldTiming.finish(true);worldTiming=null;}
      this.parity ^= 1;
      if(Boolean.getBoolean("sulkan.qaReadback"))this.diagnosticDisplay=destination.getColorTexture();
      this.frame.commit(camera, view, world);
      this.renderedFrames++;
   }

   static boolean canPresentDirectly(PackGraph graph, PackGraph.Pass last, GpuFormat format, int width, int height) {
      PackGraph.Resource output = graph.resources().get(graph.present());
      return !last.isCompute()
         && last.writes().equals(List.of(graph.present()))
         && !last.reads().containsValue("$scene")
         && !output.history()
         && !output.mipmaps()
         && output.depth() == 1
         && output.format() == format
         && output.width(width) == width
         && output.height(height) == height;
   }

   private GraphRenderer.Image image(String name) {
      GraphRenderer.Image[] pair = this.images.get(PackGraph.current(name));
      return pair[pair.length == 1 ? 0 : (PackGraph.previous(name) ? this.parity ^ 1 : this.parity)];
   }

   private void generateMipmaps(String name) {
      if (this.graph.resources().get(name).mipmaps()) {
         GraphMipmaps.generate(this.image(name).texture, this.mipFilters.get(name));
      }
   }

   private GpuTextureView resolve(String resource, RenderTarget main) {
      return switch (resource) {
         case "$scene" -> main.getColorTextureView();
         case "$weather" -> this.sceneCapture.view("$weather");
         case "$depth" -> this.sceneCapture.view("$depth");
         case "$handDepth" -> main.getDepthTextureView();
         case "$terrainOpaque", "$terrainOpaqueDepth" -> this.sceneCapture.view(resource);
         case "$terrainAtlas" -> net.minecraft.client.Minecraft.getInstance().getTextureManager()
                 .getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).getTextureView();
         default -> resource.startsWith("$texture/") ? this.customTextures.get(resource.substring(9)).view : this.image(resource).view;
      };
   }

   private GpuSampler sampler(String resource) {
      if (resource.equals("$terrainAtlas")) {
         // Atlas.getSampler() has a different filtering policy from actual terrain.
         // Resolve the borrowed sampler each frame: settings can replace it.
         GpuSampler terrain = ((com.sulkan.shaders.mixin.TerrainSamplerAccessor)
                 net.minecraft.client.Minecraft.getInstance().levelRenderer).sulkan$terrainSampler();
         if (terrain == null) throw new IllegalStateException("Terrain atlas sampling requested before the terrain sampler is ready");
         return terrain;
      }
      return resource.startsWith("$texture/") ? this.textureSamplers.get(resource.substring(9)) : this.sampler;
   }

   private void loadTextures(PackFiles files) throws IOException {
      Set<String> used = new HashSet<>();
      this.schedule.forEach(pass -> pass.reads().values().forEach(input -> {
         if (input.startsWith("$texture/")) {
            used.add(input.substring(9));
         }
      }));
      this.graph.scenePrograms().forEach(program -> program.textures().values().stream().filter(asset -> !GraphSceneCapture.isInput(asset)).forEach(used::add));
      GpuDevice device = RenderSystem.getDevice();

      for (String name : used) {
         PackGraph.Texture definition = this.graph.textures().get(name);
         byte[] png;
         if (definition.source().equals("$minecraft/clouds")) {
            try (InputStream stream = Minecraft.getInstance().getResourceManager().open(Identifier.withDefaultNamespace("textures/environment/clouds.png"))) {
               png = stream.readNBytes(8388609);
               if (png.length > 8388608) {
                  throw new IOException("Cloud texture exceeds 8 MiB");
               }
            }
         } else {
            png = files.binary(definition.source());
         }

         ByteBuffer header = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN);
         if (png.length >= 24 && header.getLong(0) == -8552249625308161526L && header.getInt(12) == 1229472850) {
            int w = header.getInt(16);
            int h = header.getInt(20);
            int limit = device.getDeviceInfo().limits().maxTextureSizeForFormat(GpuFormat.RGBA8_UNORM);
            long bytes = (long)w * h * 4L;
            if (w > 0
               && h > 0
               && w <= limit
               && h <= limit
               && bytes <= device.getDeviceInfo().limits().maxMemoryAllocationSize()
               && bytes <= this.graph.budgetBytes() - this.textureBytes) {
               NativeImage image = NativeImage.read(new ByteArrayInputStream(png));

               try {
                  GpuTexture texture = device.createTexture("Sulkan texture " + name, 5, GpuFormat.RGBA8_UNORM, w, h, 1, 1);

                  try {
                     this.customTextures.put(name, new GraphRenderer.Image(texture, device.createTextureView(texture)));
                  } catch (Exception failure) {
                     texture.close();
                     throw failure;
                  }

                  device.createCommandEncoder().writeToTexture(texture, image);
               } catch (Throwable var20) {
                  if (image != null) {
                     try {
                        image.close();
                     } catch (Throwable var17) {
                        var20.addSuppressed(var17);
                     }
                  }

                  throw var20;
               }

               if (image != null) {
                  image.close();
               }

               AddressMode wrap = definition.repeat() ? AddressMode.REPEAT : AddressMode.CLAMP_TO_EDGE;
               FilterMode filter = definition.linear() ? FilterMode.LINEAR : FilterMode.NEAREST;
               this.textureSamplers.put(name, device.createSampler(wrap, wrap, filter, filter, 1, OptionalDouble.empty()));
               this.textureBytes += bytes;
               if(Boolean.getBoolean("sulkan.qaReadback"))this.diagnosticTextureSources.put(name,png.clone());
               if (definition.source().equals("$minecraft/clouds")) {
                  this.frame.cloudTextureWidth(w);
               }
               continue;
            }

            throw new IOException("Custom texture exceeds device or pack limits: " + name);
         }

         throw new IOException("Custom textures must be PNG: " + name);
      }
   }

   public void beginScene(RenderTarget target, CameraRenderState camera, Matrix4fc view, Object world) {
      this.allocate(target.width, target.height);
      this.sceneCapture.beginFrame();
      CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

      for (String name : this.graph.sceneTargets()) {
         encoder.clearColorTexture(this.image(name).texture, new Vector4f(0.0F));
      }

      this.sceneUniforms = this.frame.upload(encoder, camera, view, this.width, this.height, this.frame.discontinuity(camera, world, view));
      this.windBytes.clear();
      this.windBytes
         .putFloat((float)(camera.pos.x - Math.floor(camera.pos.x / 4096.0) * 4096.0))
         .putFloat((float)(camera.pos.y - Math.floor(camera.pos.y / 4096.0) * 4096.0))
         .putFloat((float)(camera.pos.z - Math.floor(camera.pos.z / 4096.0) * 4096.0))
         .putFloat((float)(System.nanoTime() - WIND_START) * 1.0E-9F);
      this.windBytes.putFloat(this.graph.options().getOrDefault("VEGETATION_WIND", 0.0).floatValue()).putFloat(0.0F).putFloat(0.0F).putFloat(0.0F).flip();
      this.windUniforms = encoder.transientMemory()
         .uploadGpu(this.windBytes, RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), 128);
   }

   public GpuTextureView weatherView() {
      return this.sceneCapture.weatherView();
   }

   public void handProjection(Matrix4fc projection) {
      this.frame.handProjection(projection);
   }

   public void projection(Matrix4fc projection,float jitterX,float jitterY) {
      this.frame.projection(projection,jitterX,jitterY);
   }

   public void environment(LevelRenderState state, ClientLevel level, float partialTick) {
      this.frame.environment(state, level, partialTick);
   }

   public PackGraph.Environment environment() {
      return this.graph.environment();
   }

   public int shadowQuality() {
      return this.graph.shadowQuality();
   }

   public int shadowDistance() {
      return this.graph.shadowDistance();
   }

   public boolean animatedShadowCasters() {
      return this.graph.options().getOrDefault("VEGETATION_WIND", 0.0) > 0.0;
   }

   public RenderPipeline scenePipeline(RenderPipeline original) {
      return this.scenePrograms.replace(original, !this.graph.sceneTargets().isEmpty());
   }

   public RenderPipeline scenePipeline(RenderPipeline original, boolean attachments) {
      return this.scenePrograms.replace(original, attachments);
   }

   public RenderPipeline sceneFallback(RenderPipeline original, boolean attachments) {
      return this.scenePrograms.fallback(original, attachments);
   }

   public RenderPassDescriptor sceneAttachments(RenderPassDescriptor descriptor, RenderTarget main) {
      if (this.graph.sceneTargets().isEmpty() || descriptor.colorAttachments().size() != 1 || descriptor.colorAttachments().getFirst() == null) {
         return descriptor;
      }

      if (((Attachment)descriptor.colorAttachments().getFirst()).textureView().texture() != main.getColorTexture()) {
         return descriptor;
      }

      ArrayList<Attachment<Optional<Vector4fc>>> attachments = new ArrayList<>(descriptor.colorAttachments());

      for (String name : this.graph.sceneTargets()) {
         attachments.add(new Attachment(this.image(name).attachment, Optional.empty()));
      }

      return new RenderPassDescriptor(descriptor.label(), attachments, descriptor.depthAttachment(), descriptor.renderArea());
   }

   public boolean hasSceneAttachments(List<Attachment<Optional<Vector4fc>>> attachments) {
      return !this.graph.sceneTargets().isEmpty()
         && attachments.size() == this.graph.sceneTargets().size() + 1
         && attachments.get(1) != null
         && attachments.get(1).textureView() == this.image(this.graph.sceneTargets().getFirst()).attachment;
   }

   public void bindSceneUniforms(RenderPass pass, RenderPipeline pipeline) {
      if (ScenePrograms.isReplacement(pipeline) && this.sceneUniforms != null && this.sceneUniforms.buffer().isClosed()) {
         throw new IllegalStateException("Scene uniforms expired before terrain rendering");
      }

      if (this.windUniforms != null
         && (ScenePrograms.isReplacement(pipeline) || pipeline.getShaderDefines().flags().contains("SULKAN_VEGETATION"))
         && this.windUniforms.buffer().isClosed()) {
         throw new IllegalStateException("Wind uniforms expired before geometry rendering");
      }

      if (this.windUniforms != null && (ScenePrograms.isReplacement(pipeline) || pipeline.getShaderDefines().flags().contains("SULKAN_VEGETATION"))) {
         pass.setUniform("SulkanWind", this.windUniforms);
      }

      if (ScenePrograms.isReplacement(pipeline) && this.heldShadows != null) {
         this.heldShadows.bind(pass);
      }

      if (ScenePrograms.isReplacement(pipeline) && this.sceneUniforms != null) {
         pass.setUniform("SulkanFrame", this.sceneUniforms);
      }

      if (ScenePrograms.isReplacement(pipeline) && this.graph.shadowQuality() > 0) {
         ShadowService.bindTerrain(pass);
      }

      ScenePrograms.textures(pipeline)
         .forEach(
            (binding, asset) -> pass.setUniform(
               binding,
               GraphSceneCapture.isInput(asset) ? this.sceneCapture.view(asset) : this.customTextures.get(asset).view,
               GraphSceneCapture.isInput(asset) ? this.sampler : this.textureSamplers.get(asset)
            )
         );
   }

   private static void destroy(Map<String, GraphRenderer.Image[]> targets) {
      Set<GraphRenderer.Image[]> unique = Collections.newSetFromMap(new IdentityHashMap<>());
      unique.addAll(targets.values());
      unique.forEach(pair -> {
         for (GraphRenderer.Image image : pair) {
            if (image != null) {
               image.close();
            }
         }
      });
   }

   @Override
   public void close() {
      if (!this.closed) {
         this.closed = true;
         if(this.reconstruction!=null) { this.reconstruction.close(); this.reconstruction=null; }
         if (this.rayScene != null) {
            this.rayScene.retire(this.rayDevice.createCommandEncoder());
            this.rayScene = null;
         }
         if (this.scenePrograms != null) {
            this.scenePrograms.close();
         }

         if (this.buffers != null) {
            this.buffers.close();
         }

         if (this.sceneCapture != null) {
            this.sceneCapture.close();
         }

         if (this.heldShadows != null) {
            this.heldShadows.close();
         }

         this.computePipelines.values().forEach(ComputeProgram::close);
         this.computePipelines.clear();
         this.customTextures.values().forEach(GraphRenderer.Image::close);
         this.customTextures.clear();
         this.diagnosticTextureSources.clear();
         this.textureSamplers.values().forEach(UncheckedAutoCloseable::close);
         this.textureSamplers.clear();
         destroy(this.images);
         this.images.clear();
         this.sampler.close();
         if (this.linearSampler != null) {
            this.linearSampler.close();
         }

         this.ownedPipelines.forEach(GraphShaderSources::remove);
         this.ownedPipelines.clear();
         this.diagnosticDisplayPipelines.clear();
      }
   }

   private record Image(GpuTexture texture, GpuTextureView view, GpuTextureView attachment) implements AutoCloseable {
      Image(GpuTexture texture, GpuTextureView view) {
         this(texture, view, view);
      }

      @Override
      public void close() {
         if (this.attachment != this.view) {
            this.attachment.close();
         }

         this.view.close();
         this.texture.close();
      }
   }
}
