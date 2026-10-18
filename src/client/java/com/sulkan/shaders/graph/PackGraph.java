package com.sulkan.shaders.graph;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.renderpearl.api.GpuFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map.Entry;

public record PackGraph(
   String name,
   Map<String, PackGraph.Resource> resources,
   List<PackGraph.Pass> passes,
   String present,
   Map<String, Double> options,
   Map<String, PackGraph.Option> optionDefinitions,
   List<PackGraph.SceneProgram> scenePrograms,
   Map<String, PackGraph.Texture> textures,
   List<String> sceneTargets,
   Map<String, PackGraph.Buffer> buffers,
   Map<String, Integer> materials,
   boolean shadows,
   PackGraph.Environment environment,
   long budgetBytes,
   Reconstruction reconstruction,
   boolean cameraRelativeRays
) {
   public int shadowQuality() {
      return this.shadows ? this.options.getOrDefault("SHADOW_QUALITY", 2.0).intValue() : 0;
   }

   public int shadowDistance() {
      return this.options.getOrDefault("SHADOW_DISTANCE", 128.0).intValue();
   }

   public static PackGraph parse(String json) {
      JsonObject root = JsonParser.parseString(json).getAsJsonObject();
      required(root, "version", "name", "resources", "passes", "present");
      keys(
         root,
         "version",
         "name",
         "resources",
         "passes",
         "present",
         "options",
         "budgetMiB",
         "scenePrograms",
         "textures",
         "sceneTargets",
         "buffers",
         "materials",
         "shadows",
         "environment",
         "reconstruction",
         "rayCoordinates"
      );
      if (integer(root.get("version"), "version") != 1) {
         throw new IllegalArgumentException("Unsupported native pack version");
      }
      String rayCoordinates=root.has("rayCoordinates") ? root.get("rayCoordinates").getAsString() : "world";
      if(!rayCoordinates.equals("world") && !rayCoordinates.equals("camera_relative"))
         throw new IllegalArgumentException("rayCoordinates must be world or camera_relative");

      Map<String, Double> options = new TreeMap<>();
      Map<String, PackGraph.Option> definitions = new LinkedHashMap<>();
      if (root.has("options")) {
         root.getAsJsonObject("options").entrySet().forEach(e -> {
            if (!((String)e.getKey()).matches("[A-Z][A-Z0-9_]*")) {
               throw new IllegalArgumentException("Invalid option define " + (String)e.getKey());
            }

            JsonElement rawx = (JsonElement)e.getValue();
            List<Double> values = new ArrayList<>();
            String label = (String)e.getKey();
            double value;
            if (rawx.isJsonObject()) {
               JsonObject option = rawx.getAsJsonObject();
               keys(option, "label", "default", "values");
               required(option, "default", "values");
               label = option.has("label") ? option.get("label").getAsString() : label;
               value = option.get("default").getAsDouble();
               option.getAsJsonArray("values").forEach(v -> values.add(v.getAsDouble()));
               if (values.isEmpty() || values.size() > 128 || !values.contains(value) || values.stream().anyMatch(v -> !Double.isFinite(v))) {
                  throw new IllegalArgumentException("Invalid choices for " + (String)e.getKey());
               }
            } else {
               value = rawx.getAsDouble();
               values.add(value);
            }

            if (!Double.isFinite(value)) {
               throw new IllegalArgumentException("Non-finite option " + (String)e.getKey());
            }

            options.put((String)e.getKey(), value);
            definitions.put((String)e.getKey(), new PackGraph.Option(label, value, List.copyOf(values)));
         });
      }

      if (!root.has("shadows") || root.get("shadows").isJsonPrimitive() && root.getAsJsonPrimitive("shadows").isBoolean()) {
         if (root.has("shadows") && root.get("shadows").getAsBoolean()) {
            for (String control : List.of("SHADOW_QUALITY", "SHADOW_DISTANCE")) {
               PackGraph.Option option = definitions.get(control);
               if (option != null
                  && option.values()
                     .stream()
                     .anyMatch(v -> v != Math.rint(v) || (control.equals("SHADOW_QUALITY") ? v < 0.0 || v > 4.0 : v < 32.0 || v > 256.0))) {
                  throw new IllegalArgumentException("Invalid native shadow control " + control);
               }
            }
         }

         Map<String, PackGraph.Resource> resources = new LinkedHashMap<>();
         root.getAsJsonObject("resources")
            .entrySet()
            .forEach(
               e -> {
                  identifier((String)e.getKey());
                  JsonObject r = ((JsonElement)e.getValue()).getAsJsonObject();
                  keys(r, "format", "scale", "history", "size", "mipmaps");
                  required(r, "format");
                  GpuFormat format = GpuFormat.valueOf(r.get("format").getAsString());
                  if (!format.hasColorAspect()) {
                     throw new IllegalArgumentException("Graph output must be a color format");
                  }

                  double scale = r.has("scale") ? r.get("scale").getAsDouble() : 1.0;
                  if (Double.isFinite(scale) && !(scale <= 0.0) && !(scale > 2.0)) {
                     int width = 0;
                     int height = 0;
                     int depth = 1;
                     if (r.has("size")) {
                        if (r.has("scale")) {
                           throw new IllegalArgumentException("Use size or scale, not both");
                        }

                        JsonArray size = r.getAsJsonArray("size");
                        if (size.size() != 3) {
                           throw new IllegalArgumentException("Resource size must be [width,height,depth]");
                        }

                        width = integer(size.get(0), "width");
                        height = integer(size.get(1), "height");
                        depth = integer(size.get(2), "depth");
                        if (width < 1 || height < 1 || depth < 1 || width > 16384 || height > 16384 || depth > 2048) {
                           throw new IllegalArgumentException("Invalid resource size");
                        }
                     }

                     resources.put(
                        (String)e.getKey(),
                        new PackGraph.Resource(
                           format,
                           scale,
                           r.has("history") && r.get("history").getAsBoolean(),
                           width,
                           height,
                           depth,
                           r.has("mipmaps") && r.get("mipmaps").getAsBoolean()
                        )
                     );
                  } else {
                     throw new IllegalArgumentException("Resource scale must be in (0, 2]");
                  }
               }
            );
         Map<String, PackGraph.Buffer> buffers = new LinkedHashMap<>();
         if (root.has("buffers")) {
            root.getAsJsonObject("buffers").entrySet().forEach(e -> {
               identifier((String)e.getKey());
               JsonObject b = ((JsonElement)e.getValue()).getAsJsonObject();
               keys(b, "bytes", "history");
               long bytes = integer(b.get("bytes"), "bytes");
               if (bytes >= 4L && bytes % 4L == 0L && bytes <= 536870912L) {
                  buffers.put((String)e.getKey(), new PackGraph.Buffer(bytes, b.has("history") && b.get("history").getAsBoolean()));
               } else {
                  throw new IllegalArgumentException("Buffer size must be a multiple of 4, up to 512 MiB: " + (String)e.getKey());
               }
            });
         }

         if (buffers.size() > 64) {
            throw new IllegalArgumentException("Too many storage buffers");
         }

         List<PackGraph.Pass> passes = new ArrayList<>();
         Set<String> names = new HashSet<>();

         for (JsonElement item : root.getAsJsonArray("passes")) {
            JsonObject p = item.getAsJsonObject();
            keys(p, "name", "fragment", "compute", "localSize", "reads", "writes", "buffers", "dispatch", "readConditions", "linearReads", "rayScene", "rayAttributes");
            required(p, "name", "reads", "writes");
            String name = p.get("name").getAsString();
            identifier(name);
            if (!names.add(name)) {
               throw new IllegalArgumentException("Duplicate pass " + name);
            }

            if (p.has("fragment") == p.has("compute")) {
               throw new IllegalArgumentException("Pass must declare exactly one of fragment or compute");
            }

            String fragment = p.has("fragment") ? p.get("fragment").getAsString() : null;
            String compute = p.has("compute") ? p.get("compute").getAsString() : null;
            if (p.has("rayScene") && (!p.get("rayScene").isJsonPrimitive() || !p.getAsJsonPrimitive("rayScene").isBoolean())) {
               throw new IllegalArgumentException("rayScene must be a boolean");
            }
            boolean rayScene = p.has("rayScene") && p.get("rayScene").getAsBoolean();
            if (rayScene && compute == null) throw new IllegalArgumentException("rayScene requires a compute pass");
            if (p.has("rayAttributes") && (!p.get("rayAttributes").isJsonPrimitive() || !p.getAsJsonPrimitive("rayAttributes").isBoolean())) {
               throw new IllegalArgumentException("rayAttributes must be a boolean");
            }
            boolean rayAttributes = p.has("rayAttributes") && p.get("rayAttributes").getAsBoolean();
            if (rayAttributes && !rayScene) throw new IllegalArgumentException("rayAttributes requires rayScene");
            if (!PackFiles.safe(fragment == null ? compute : fragment)) {
               throw new IllegalArgumentException("Invalid shader path");
            }

            List<Integer> local = new ArrayList<>(List.of(8, 8, 1));
            if (p.has("localSize")) {
               if (compute == null) {
                  throw new IllegalArgumentException("Only compute passes have localSize");
               }

               local.clear();
               p.getAsJsonArray("localSize").forEach(e -> local.add(integer(e, "localSize")));
               if (local.size() != 3 || local.stream().anyMatch(v -> v < 1 || v > 1024)) {
                  throw new IllegalArgumentException("Compute localSize must be [x,y,z]");
               }
            }

            Map<String, String> reads = new LinkedHashMap<>();
            p.getAsJsonObject("reads").entrySet().forEach(e -> {
               if (!((String)e.getKey()).matches("[A-Za-z_][A-Za-z0-9_]*")) {
                  throw new IllegalArgumentException("Invalid sampler " + (String)e.getKey());
               }

               reads.put((String)e.getKey(), ((JsonElement)e.getValue()).getAsString());
            });
            List<String> writes = new ArrayList<>();
            p.getAsJsonArray("writes").forEach(e -> writes.add(e.getAsString()));
            Map<String, PackGraph.BufferBinding> bindings = new LinkedHashMap<>();
            if (p.has("buffers")) {
               p.getAsJsonObject("buffers")
                  .entrySet()
                  .forEach(
                     e -> {
                        if (compute == null) {
                           throw new IllegalArgumentException("Storage buffers currently require compute");
                        }

                        JsonObject bindingx = ((JsonElement)e.getValue()).getAsJsonObject();
                        keys(bindingx, "resource", "access");
                        required(bindingx, "resource", "access");
                        String resourcex = bindingx.get("resource").getAsString();
                        String access = bindingx.get("access").getAsString();
                        if (List.of("read", "write").contains(access)
                           && buffers.containsKey(current(resourcex))
                           && (!previous(resourcex) || !access.equals("write") && buffers.get(current(resourcex)).history)) {
                           bindings.put((String)e.getKey(), new PackGraph.BufferBinding(resourcex, access.equals("write")));
                        } else {
                           throw new IllegalArgumentException("Invalid buffer binding " + (String)e.getKey());
                        }
                     }
                  );
            }

            Set<String> shaderNames = new HashSet<>();
            shaderNames.addAll(reads.keySet());

            for (String binding : bindings.keySet()) {
               if (!shaderNames.add(binding)) {
                  throw new IllegalArgumentException("Duplicate shader binding " + binding);
               }
            }

            for (String binding : shaderNames) {
               if (!binding.matches("[A-Za-z_][A-Za-z0-9_]*") || binding.startsWith("gl_") || binding.startsWith("Sulkan") || binding.matches("Output[0-9]+")) {
                  throw new IllegalArgumentException("Invalid or reserved shader binding " + binding);
               }
            }

            List<Integer> dispatch = new ArrayList<>();
            if (p.has("dispatch")) {
               p.getAsJsonArray("dispatch").forEach(e -> dispatch.add(integer(e, "dispatch")));
            }

            if (dispatch.isEmpty() || compute != null && dispatch.size() == 3 && !dispatch.stream().anyMatch(v -> v < 1 || v > 16777216)) {
               if (rayScene && (writes.isEmpty() || bindings.values().stream().anyMatch(PackGraph.BufferBinding::write))) {
                  throw new IllegalArgumentException("Ray passes require image outputs and cannot write storage buffers");
               }
               if (writes.size() <= 8
                  && new HashSet<>(writes).size() == writes.size()
                  && (!writes.isEmpty() || compute != null && !dispatch.isEmpty() && !bindings.values().stream().noneMatch(PackGraph.BufferBinding::write))) {
                  passes.add(
                     new PackGraph.Pass(
                        name,
                        fragment,
                        compute,
                        List.copyOf(local),
                        Collections.unmodifiableMap(reads),
                        List.copyOf(writes),
                        Collections.unmodifiableMap(bindings),
                        List.copyOf(dispatch), Map.of(), Set.of(), rayScene, rayAttributes
                     )
                  );
                  continue;
               }

               throw new IllegalArgumentException("Pass requires image outputs or a buffer writer with explicit dispatch: " + name);
            }

            throw new IllegalArgumentException("dispatch must specify three positive compute extents");
         }

         if (!passes.isEmpty() && passes.size() <= 128 && resources.size() <= 256) {
            String present = root.get("present").getAsString();
            Map<String, PackGraph.Texture> textures = new LinkedHashMap<>();
            if (root.has("textures")) {
               root.getAsJsonObject("textures")
                  .entrySet()
                  .forEach(
                     e -> {
                        identifier((String)e.getKey());
                        JsonObject texture = ((JsonElement)e.getValue()).getAsJsonObject();
                        keys(texture, "source", "filter", "wrap");
                        String path = texture.get("source").getAsString();
                        String filter = texture.has("filter") ? texture.get("filter").getAsString() : "nearest";
                        String wrap = texture.has("wrap") ? texture.get("wrap").getAsString() : "clamp";
                        if ((path.equals("$minecraft/clouds") || PackFiles.safe(path))
                           && List.of("nearest", "linear").contains(filter)
                           && List.of("clamp", "repeat").contains(wrap)) {
                           textures.put((String)e.getKey(), new PackGraph.Texture(path, filter.equals("linear"), wrap.equals("repeat")));
                        } else {
                           throw new IllegalArgumentException("Invalid custom texture " + (String)e.getKey());
                        }
                     }
                  );
            }

            List<String> sceneTargets = new ArrayList<>();
            if (root.has("sceneTargets")) {
               root.getAsJsonArray("sceneTargets").forEach(e -> sceneTargets.add(e.getAsString()));
            }

            if (sceneTargets.size() <= 7 && new HashSet<>(sceneTargets).size() == sceneTargets.size()) {
               for (String target : sceneTargets) {
                  PackGraph.Resource resource = resources.get(target);
                  if (resource == null || resource.history || resource.depth != 1 || resource.scale != 1.0 || resource.fixedWidth != 0) {
                     throw new IllegalArgumentException("Scene targets must be full-resolution, non-history 2D resources: " + target);
                  }
               }

               List<PackGraph.SceneProgram> scenePrograms = new ArrayList<>();
               if (root.has("scenePrograms")) {
                  for (JsonElement entry : root.getAsJsonArray("scenePrograms")) {
                     JsonObject rule = entry.getAsJsonObject();
                     keys(rule, "pipeline", "vertex", "fragment", "writes", "textures");
                     String selector = rule.get("pipeline").getAsString();
                     String vertex = rule.get("vertex").getAsString();
                     String fragment = rule.get("fragment").getAsString();
                     if (!selector.matches("[a-z0-9_.-]+:[a-z0-9/_.-]+\\*?") || !PackFiles.safe(vertex) || !PackFiles.safe(fragment)) {
                        throw new IllegalArgumentException("Invalid scene program selector or path");
                     }

                     List<String> writes = new ArrayList<>();
                     if (rule.has("writes")) {
                        rule.getAsJsonArray("writes").forEach(e -> writes.add(e.getAsString()));
                     }

                     if (!sceneTargets.containsAll(writes) || new HashSet<>(writes).size() != writes.size()) {
                        throw new IllegalArgumentException("Scene program writes must name distinct sceneTargets");
                     }

                     Map<String, String> assets = new LinkedHashMap<>();
                     if (rule.has("textures")) {
                        rule.getAsJsonObject("textures")
                           .entrySet()
                           .forEach(
                              e -> {
                                 String bindingx = (String)e.getKey();
                                 String asset = ((JsonElement)e.getValue()).getAsString();
                                 if (bindingx.matches("[A-Za-z_][A-Za-z0-9_]*")
                                    && !bindingx.startsWith("gl_")
                                    && !bindingx.startsWith("Sulkan")
                                    && (textures.containsKey(asset) || GraphSceneCapture.isInput(asset))) {
                                    assets.put(bindingx, asset);
                                 } else {
                                    throw new IllegalArgumentException("Invalid scene texture binding " + bindingx);
                                 }
                              }
                           );
                     }

                     scenePrograms.add(new PackGraph.SceneProgram(selector, vertex, fragment, List.copyOf(writes), Collections.unmodifiableMap(assets)));
                  }
               }

               long budgetMiB = root.has("budgetMiB") ? integer(root.get("budgetMiB"), "budgetMiB") : 512L;
               Map<String, Integer> materials = new LinkedHashMap<>();
               if (root.has("materials")) {
                  root.getAsJsonObject("materials").entrySet().forEach(e -> {
                     int id = integer((JsonElement)e.getValue(), "material ID");
                     if (id >= 1 && id <= 65535 && ((String)e.getKey()).matches("[a-z0-9_.-]+:[a-z0-9/_.-]+(\\[[a-z0-9_=,.-]+\\])?")) {
                        materials.put((String)e.getKey(), id);
                     } else {
                        throw new IllegalArgumentException("Invalid material selector or ID: " + (String)e.getKey());
                     }
                  });
               }

               if (materials.size() > 16384) {
                  throw new IllegalArgumentException("Too many material selectors");
               }

               if (budgetMiB >= 1L && budgetMiB <= 2048L) {
                  PackGraph.Environment environment = new PackGraph.Environment(false, false);
                  if (root.has("environment")) {
                     JsonObject env = root.getAsJsonObject("environment");
                     keys(env, "sky", "clouds");

                     for (Entry<String, JsonElement> field : env.entrySet()) {
                        if (!field.getValue().isJsonPrimitive() || !field.getValue().getAsJsonPrimitive().isBoolean()) {
                           throw new IllegalArgumentException("Environment flags must be boolean");
                        }
                     }

                     environment = new PackGraph.Environment(
                        env.has("sky") && env.get("sky").getAsBoolean(), env.has("clouds") && env.get("clouds").getAsBoolean()
                     );
                  }

                  for (int i = 0; i < passes.size(); i++) {
                     JsonObject raw = root.getAsJsonArray("passes").get(i).getAsJsonObject();
                     PackGraph.Pass pass = passes.get(i);
                     Map<String, List<List<String>>> conditions = new LinkedHashMap<>();
                     if (raw.has("readConditions")) {
                        raw.getAsJsonObject("readConditions").entrySet().forEach(e -> {
                           if (!pass.reads.containsKey(e.getKey())) {
                              throw new IllegalArgumentException("Unknown conditional sampler " + (String)e.getKey());
                           }

                           List<List<String>> controls = new ArrayList<>();

                           for (JsonElement control : ((JsonElement)e.getValue()).getAsJsonArray()) {
                              List<String> clause = new ArrayList<>();
                              if (control.isJsonArray()) {
                                 control.getAsJsonArray().forEach(term -> clause.add(term.getAsString()));
                              } else {
                                 clause.add(control.getAsString());
                              }

                              if (clause.isEmpty()) {
                                 throw new IllegalArgumentException("Empty read conjunction");
                              }

                              for (String keyx : clause) {
                                 if (!options.containsKey(keyx)) {
                                    throw new IllegalArgumentException("Unknown read condition " + keyx);
                                 }
                              }

                              controls.add(List.copyOf(clause));
                           }

                           if (controls.isEmpty()) {
                              throw new IllegalArgumentException("Empty read condition");
                           }

                           conditions.put((String)e.getKey(), List.copyOf(controls));
                        });
                     }

                     Set<String> linear = new HashSet<>();
                     if (raw.has("linearReads")) {
                        for (JsonElement binding : raw.getAsJsonArray("linearReads")) {
                           String key = binding.getAsString();
                           if (!pass.reads.containsKey(key)) {
                              throw new IllegalArgumentException("Unknown linear sampler " + key);
                           }

                           String resource = current(pass.reads.get(key));
                           if (!resources.containsKey(resource) || resources.get(resource).format.name().endsWith("_UINT")) {
                              throw new IllegalArgumentException("Linear reads require a filterable graph color resource");
                           }

                           linear.add(key);
                        }
                     }

                     passes.set(
                        i,
                        new PackGraph.Pass(
                           pass.name,
                           pass.fragment,
                           pass.compute,
                           pass.localSize,
                           pass.reads,
                           pass.writes,
                           pass.buffers,
                           pass.dispatch,
                           Map.copyOf(conditions),
                           Set.copyOf(linear), pass.rayScene, pass.rayAttributes
                        )
                     );
                  }

                  PackGraph graph = new PackGraph(
                     root.get("name").getAsString(),
                     Collections.unmodifiableMap(resources),
                     List.copyOf(passes),
                     present,
                     Map.copyOf(options),
                     Collections.unmodifiableMap(definitions),
                     List.copyOf(scenePrograms),
                     Collections.unmodifiableMap(textures),
                     List.copyOf(sceneTargets),
                     Collections.unmodifiableMap(buffers),
                     Collections.unmodifiableMap(materials),
                     root.has("shadows") && root.get("shadows").getAsBoolean(),
                     environment,
                     budgetMiB * 1024L * 1024L,
                     Reconstruction.parse(root.get("reconstruction"),resources,present),
                     rayCoordinates.equals("camera_relative")
                  );
                  graph.schedule();
                  return graph;
               } else {
                  throw new IllegalArgumentException("budgetMiB must be 1..2048");
               }
            } else {
               throw new IllegalArgumentException("sceneTargets must contain up to 7 distinct resources");
            }
         } else {
            throw new IllegalArgumentException("Graph exceeds resource/pass limits");
         }
      } else {
         throw new IllegalArgumentException("shadows must be boolean");
      }
   }

   public PackGraph withOptions(Map<String, Double> overrides) {
      Map<String, Double> selected = new TreeMap<>(this.options);
      overrides.forEach((key, value) -> {
         PackGraph.Option definition = this.optionDefinitions.get(key);
         if (definition != null && definition.values.contains(value)) {
            selected.put(key, value);
         } else {
            throw new IllegalArgumentException("Unsupported option value " + key + "=" + value);
         }
      });
      return new PackGraph(
         this.name,
         this.resources,
         this.passes,
         this.present,
         Map.copyOf(selected),
         this.optionDefinitions,
         this.scenePrograms,
         this.textures,
         this.sceneTargets,
         this.buffers,
         this.materials,
         this.shadows,
         this.environment,
         this.budgetBytes,
         this.reconstruction,
         this.cameraRelativeRays
      );
   }

   public List<PackGraph.Pass> schedule() {
      Map<String, PackGraph.Pass> producers = new HashMap<>();

      for (PackGraph.Pass pass : this.passes) {
         for (String output : pass.outputs()) {
            if (output.startsWith("$buffer/") && producers.put(output, pass) != null) {
               throw new IllegalArgumentException("Multiple writers for " + output);
            }
         }

         PackGraph.Resource shape = null;

         for (String output : pass.writes) {
            if (this.sceneTargets.contains(output)) {
               throw new IllegalArgumentException("Scene target cannot also have a post-scene writer: " + output);
            }

            PackGraph.Resource resource = this.resources.get(output);
            if (resource == null) {
               throw new IllegalArgumentException("Undeclared output " + output);
            }

            if (shape != null && !shape.sameShape(resource)) {
               throw new IllegalArgumentException("Pass outputs must have equal dimensions: " + pass.name);
            }

            shape = resource;
            if (!pass.isCompute() && resource.depth > 1) {
               throw new IllegalArgumentException("Volume outputs require compute: " + output);
            }

            if (producers.put(output, pass) != null) {
               throw new IllegalArgumentException("Multiple writers for " + output + "; use a new resource name");
            }
         }
      }

      if (!producers.containsKey(this.present) && !this.sceneTargets.contains(this.present)) {
         throw new IllegalArgumentException("No producer for presented resource " + this.present);
      }

      if (this.resources.get(this.present).depth > 1) {
         throw new IllegalArgumentException("Cannot present a volume");
      }

      if (!this.resources.get(this.present).format.name().endsWith("_UINT") && !this.resources.get(this.present).format.name().endsWith("_SINT")) {
         for (PackGraph.Pass pass : this.passes) {
            for (String input : pass.inputs()) {
               if (!input.equals("$scene")
                  && !input.equals("$depth")
                  && !input.equals("$handDepth")
                  && !input.equals("$weather")
                  && !input.equals("$terrainAtlas")
                  && !GraphSceneCapture.isInput(input)
                  && (!input.startsWith("$texture/") || !this.textures.containsKey(input.substring(9)))) {
                  String name = current(input);
                  if (!producers.containsKey(name) && !this.sceneTargets.contains(name)) {
                     throw new IllegalArgumentException("No producer for " + input);
                  }

                  if (previous(input) && (name.startsWith("$buffer/") ? !this.buffers.get(name.substring(8)).history : !this.resources.get(name).history)) {
                     throw new IllegalArgumentException("Previous-frame input is not history: " + input);
                  }

                  if (!previous(input) && pass.outputs().contains(input)) {
                     throw new IllegalArgumentException("Resource feedback in " + pass.name);
                  }
               }
            }
         }

         List<PackGraph.Pass> result = new ArrayList<>();
         Map<PackGraph.Pass, Integer> state = new HashMap<>();

         for (PackGraph.Pass pass : this.passes) {
            visit(pass, producers, state, new ArrayList<>());
         }

         state.clear();
         producers.replaceAll((resourcex, passx) -> passx.selected(this.options));
         visit(producers.get(this.present), producers, state, result);
         if(this.reconstruction!=null) {
            for(String input:this.reconstruction.graphInputs()) {
               if(!producers.containsKey(input) && !this.sceneTargets.contains(input))
                  throw new IllegalArgumentException("No producer for reconstruction input "+input);
               visit(producers.get(input),producers,state,result);
            }
         }

         for (int i = 0; i < result.size(); i++) {
            for (String input : result.get(i).inputs()) {
               if (previous(input)) {
                  visit(producers.get(current(input)), producers, state, result);
               }
            }
         }

         return List.copyOf(result);
      } else {
         throw new IllegalArgumentException("Presented resource must use a normalized or floating-point format");
      }
   }

   private static void visit(PackGraph.Pass pass, Map<String, PackGraph.Pass> producers, Map<PackGraph.Pass, Integer> state, List<PackGraph.Pass> result) {
      if (pass != null) {
         int mark = state.getOrDefault(pass, 0);
         if (mark == 1) {
            throw new IllegalArgumentException("Pass dependency cycle at " + pass.name);
         }

         if (mark != 2) {
            state.put(pass, 1);

            for (String input : pass.inputs()) {
               if ((!input.startsWith("$") || input.startsWith("$buffer/")) && !previous(input)) {
                  visit(producers.get(input), producers, state, result);
               }
            }

            state.put(pass, 2);
            result.add(pass);
         }
      }
   }

   public long allocationBytes(int width, int height) {
      long bytes = this.bufferBytes();

      for (PackGraph.Resource r : GraphAllocations.plan(this).slots()) {
         bytes = Math.addExact(bytes, Math.multiplyExact(r.bytes(width, height), r.history ? 2 : 1));
      }

      if (bytes > this.budgetBytes) {
         throw new IllegalArgumentException("Pack requires " + bytes / 1048576L + " MiB of targets; budget is " + this.budgetBytes / 1048576L + " MiB");
      } else {
         return bytes;
      }
   }

   public long bufferBytes() {
      Set<String> used = new HashSet<>();
      this.schedule().forEach(p -> p.buffers.values().forEach(b -> used.add(current(b.resource))));
      return used.stream().mapToLong(name -> this.buffers.get(name).bytes * (this.buffers.get(name).history ? 2 : 1)).sum();
   }

   public static boolean previous(String name) {
      return name.endsWith("@previous");
   }

   public static String current(String name) {
      return previous(name) ? name.substring(0, name.length() - 9) : name;
   }

   private static void identifier(String name) {
      if (!name.matches("[a-z][a-z0-9_.-]*")) {
         throw new IllegalArgumentException("Invalid resource/pass name " + name);
      }
   }

   private static void keys(JsonObject object, String... allowed) {
      if (object == null) {
         throw new IllegalArgumentException("Missing native pack object");
      }

      Set<String> valid = Set.of(allowed);

      for (String key : object.keySet()) {
         if (!valid.contains(key)) {
            throw new IllegalArgumentException("Unknown native pack field: " + key);
         }
      }
   }

   private static void required(JsonObject object, String... fields) {
      for (String field : fields) {
         if (!object.has(field) || object.get(field).isJsonNull()) {
            throw new IllegalArgumentException("Missing native pack field: " + field);
         }
      }
   }

   private static int integer(JsonElement value, String field) {
      if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
         try {
            return value.getAsBigDecimal().intValueExact();
         } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Expected 32-bit integer for " + field, invalid);
         }
      } else {
         throw new IllegalArgumentException("Expected integer for " + field);
      }
   }

   public record Buffer(long bytes, boolean history) {
   }

   public record BufferBinding(String resource, boolean write) {
   }

   public record Environment(boolean sky, boolean clouds) {
   }

   public record Option(String label, double defaultValue, List<Double> values) {
   }

   public record Pass(
      String name,
      String fragment,
      String compute,
      List<Integer> localSize,
      Map<String, String> reads,
      List<String> writes,
      Map<String, PackGraph.BufferBinding> buffers,
      List<Integer> dispatch,
      Map<String, List<List<String>>> readConditions,
      Set<String> linearReads,
      boolean rayScene,
      boolean rayAttributes
   ) {
      public Pass(
         String name,
         String fragment,
         String compute,
         List<Integer> localSize,
         Map<String, String> reads,
         List<String> writes,
         Map<String, PackGraph.BufferBinding> buffers,
         List<Integer> dispatch
      ) {
         this(name, fragment, compute, localSize, reads, writes, buffers, dispatch, Map.of(), Set.of(), false);
      }

      public Pass(String name, String fragment, String compute, List<Integer> localSize,
                  Map<String, String> reads, List<String> writes, Map<String, PackGraph.BufferBinding> buffers,
                  List<Integer> dispatch, Map<String, List<List<String>>> readConditions, Set<String> linearReads,
                  boolean rayScene) {
         this(name, fragment, compute, localSize, reads, writes, buffers, dispatch, readConditions, linearReads, rayScene, false);
      }

      public Pass(String name, String fragment, String compute, List<Integer> localSize,
                  Map<String, String> reads, List<String> writes, Map<String, PackGraph.BufferBinding> buffers,
                  List<Integer> dispatch, Map<String, List<List<String>>> readConditions, Set<String> linearReads) {
         this(name, fragment, compute, localSize, reads, writes, buffers, dispatch, readConditions, linearReads, false);
      }

      PackGraph.Pass selected(Map<String, Double> options) {
         Map<String, String> active = new LinkedHashMap<>(this.reads);
         this.readConditions.forEach((binding, controls) -> {
            if (controls.stream().noneMatch(clause -> clause.stream().allMatch(key -> options.getOrDefault(key, 0.0) > 0.0))) {
               active.remove(binding);
            }
         });
         return new PackGraph.Pass(
            this.name,
            this.fragment,
            this.compute,
            this.localSize,
            Collections.unmodifiableMap(active),
            this.writes,
            this.buffers,
            this.dispatch,
            this.readConditions,
            this.linearReads, this.rayScene, this.rayAttributes
         );
      }

      public boolean isCompute() {
         return this.compute != null;
      }

      List<String> inputs() {
         List<String> inputs = new ArrayList<>(this.reads.values());
         this.buffers.values().stream().filter(b -> !b.write).forEach(b -> inputs.add("$buffer/" + b.resource));
         return inputs;
      }

      List<String> outputs() {
         List<String> outputs = new ArrayList<>(this.writes);
         this.buffers.values().stream().filter(PackGraph.BufferBinding::write).forEach(b -> outputs.add("$buffer/" + b.resource));
         return outputs;
      }
   }

   public record Resource(GpuFormat format, double scale, boolean history, int fixedWidth, int fixedHeight, int depth, boolean mipmaps) {
      public int width(int extent) {
         return this.fixedWidth > 0 ? this.fixedWidth : Math.max(1, (int)Math.ceil(extent * this.scale));
      }

      public int height(int extent) {
         return this.fixedHeight > 0 ? this.fixedHeight : Math.max(1, (int)Math.ceil(extent * this.scale));
      }

      public boolean sameShape(PackGraph.Resource other) {
         return this.scale == other.scale && this.fixedWidth == other.fixedWidth && this.fixedHeight == other.fixedHeight && this.depth == other.depth;
      }

      public int mipLevels(int width, int height) {
         return this.mipmaps ? 32 - Integer.numberOfLeadingZeros(Math.max(Math.max(this.width(width), this.height(height)), this.depth)) : 1;
      }

      public long bytes(int width, int height) {
         long bytes = 0L;
         int w = this.width(width);
         int h = this.height(height);
         int d = this.depth;

         for (int mip = 0; mip < this.mipLevels(width, height); mip++) {
            bytes = Math.addExact(bytes, (long)w * h * d * this.format.blockSize());
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
            d = Math.max(1, d / 2);
         }

         return bytes;
      }
   }

   public record SceneProgram(String pipeline, String vertex, String fragment, List<String> writes, Map<String, String> textures) {
      public boolean matches(String name) {
         return this.pipeline.endsWith("*") ? name.startsWith(this.pipeline.substring(0, this.pipeline.length() - 1)) : name.equals(this.pipeline);
      }
   }

   public record Texture(String source, boolean linear, boolean repeat) {
   }
}
