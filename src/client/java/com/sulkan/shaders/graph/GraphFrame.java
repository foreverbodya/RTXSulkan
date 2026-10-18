package com.sulkan.shaders.graph;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.sulkan.shaders.render.sky.CustomCelestials;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

final class GraphFrame {
   private static final boolean QA_TEMPORAL=Boolean.getBoolean("sulkan.qaTemporalProfile");
   private static float[] qaFixedEnvironment;
   static com.sulkan.shaders.diagnostics.QaTemporalProfile.FrameClock qaClock() {
      return QA_TEMPORAL?com.sulkan.shaders.diagnostics.QaTemporalProfile.clock():null;
   }
   static final String MEMBERS = "mat4 Projection; mat4 View; mat4 InverseProjection; mat4 InverseView;\nmat4 PreviousProjection; mat4 PreviousView;\nvec4 CameraDeltaAndHistoryValid; vec4 TimeDeltaFrame; vec4 ViewSizeAndInverse;\nvec4 WorldTimeWeatherDimension; vec4 SunDirectionAndRainBrightness; vec4 MoonDirectionAndPhase;\nvec4 CameraPositionHighAndFogType; vec4 CameraPositionLowAndFarPlane;\nvec4 FogColorAndStart; vec4 FogDistances; vec4 SkyColorAndStarBrightness;\nvec4 CloudOffsetAndGameTime;\nmat4 InverseHandProjection; vec4 HandProjectionValid;\n      vec4 HeldLightPositionRadius; vec4 HeldLightColor;\n      mat4 HeldLightViewProjection[6];\nvec4 CurrentAndPreviousJitter;\nvec4 RaySceneOrigin; vec4 RayCameraPosition;\nuvec4 RaySceneState;\nuvec4 RaySceneFlags;\n";
   private final Matrix4f previousProjection = new Matrix4f();
   private final Matrix4f previousView = new Matrix4f();
   private final ByteBuffer bytes = ByteBuffer.allocateDirect(1152).order(ByteOrder.nativeOrder());
   private long raySceneGeneration;
   private int rayScenePending;
   private boolean raySceneAvailable;
   private long emissionAddress;
   private int emissionTriangles;
   void emissionSources(long address,int count){emissionAddress=address;emissionTriangles=count;}
   void raySceneState(long generation,int pending,boolean available) { this.raySceneGeneration=generation;this.rayScenePending=pending;this.raySceneAvailable=available; }
   private com.sulkan.shaders.render.raytracing.RayOrigin rayOrigin=com.sulkan.shaders.render.raytracing.RayOrigin.WORLD;
   void rayOrigin(com.sulkan.shaders.render.raytracing.RayOrigin origin) { this.rayOrigin=origin; }
   private float jitterX,jitterY,previousJitterX,previousJitterY;
   float jitterX() { return this.jitterX; }
   float jitterY() { return this.jitterY; }
   private final float[] environment = new float[36];
   private float partialTick;
   final HeldLight heldLight = new HeldLight();
   boolean heldLighting;
   private Matrix4f worldProjection;
   private Matrix4f inverseHandProjection;
   private int cloudTextureWidth = 256;
   private Object world;
   private double x;
   private double y;
   private double z;
   private long lastTime;
   private long start = System.nanoTime();
   private int frame;
   private boolean valid;
   private boolean frameReset;

   static String declaration() {
      return "layout(std140) uniform SulkanFrame {\n" + MEMBERS + "};\n";
   }

   static boolean cameraReady(CameraRenderState camera, Matrix4fc view) {
      return camera != null
         && camera.pos != null
         && camera.projectionMatrix != null
         && matrixReady(camera.projectionMatrix)
         && (view == null || matrixReady(view));
   }

   private static boolean matrixReady(Matrix4fc matrix) {
      return matrix.isFinite() && Math.abs(matrix.determinant()) >= 1.0E-12F;
   }

   void handProjection(Matrix4fc projection) {
      this.inverseHandProjection = projection != null && matrixReady(projection) ? new Matrix4f(projection).invert() : null;
   }

   Matrix4fc inverseHandProjection() {
      return this.inverseHandProjection;
   }

   void projection(Matrix4fc projection,float jitterX,float jitterY) {
      this.inverseHandProjection = null;
      this.worldProjection = projection == null ? null : new Matrix4f(projection);
      this.jitterX=jitterX; this.jitterY=jitterY;
   }

   Matrix4fc projection(CameraRenderState camera) {
      return this.worldProjection != null ? this.worldProjection : camera.projectionMatrix;
   }

   void cloudTextureWidth(int width) {
      this.cloudTextureWidth = width;
   }

   static float cloudOffset(long ticks, float partialTick) {
      return cloudOffset(ticks, partialTick, 256);
   }

   static float cloudOffset(long ticks, float partialTick, int width) {
      return ((float)(ticks % (width * 400L)) + partialTick) * 0.030000001F;
   }

   void environment(LevelRenderState state, ClientLevel level, float partialTick) {
      if(qaClock()!=null)partialTick=0;
      Arrays.fill(this.environment, 0.0F);
      this.partialTick = partialTick;
      this.heldLight.update(level, partialTick, this.heldLighting);
      if (level != null) {
         this.environment[0] = (float)Math.floorMod(level.getDefaultClockTime(), 24000L) + partialTick;
         this.environment[1] = level.getRainLevel(partialTick);
         this.environment[2] = level.getThunderLevel(partialTick);
         this.environment[3] = level.dimension().equals(Level.NETHER)
            ? -1.0F
            : (level.dimension().equals(Level.END) ? 1.0F : (level.dimension().equals(Level.OVERWORLD) ? 0.0F : 2.0F));
      }

      if (state != null) {
         this.environment[32] = cloudOffset(state.gameTime, partialTick, this.cloudTextureWidth);
         this.environment[33] = state.cloudHeight;
         if (level != null && state.cameraRenderState != null && state.cameraRenderState.pos != null) {
            Vec3 eye = state.cameraRenderState.pos;
            double px = eye.x - 0.5;
            double py = eye.y - 0.5;
            double pz = eye.z - 0.5;
            int bx = (int)Math.floor(px);
            int by = (int)Math.floor(py);
            int bz = (int)Math.floor(pz);
            float fx = (float)(px - bx);
            float fy = (float)(py - by);
            float fz = (float)(pz - bz);

            for (int ix = 0; ix < 2; ix++) {
               for (int iy = 0; iy < 2; iy++) {
                  for (int iz = 0; iz < 2; iz++) {
                     float weight = (ix == 0 ? 1.0F - fx : fx) * (iy == 0 ? 1.0F - fy : fy) * (iz == 0 ? 1.0F - fz : fz);
                     this.environment[34] = this.environment[34]
                        + weight * level.getBrightness(LightLayer.SKY, new BlockPos(bx + ix, by + iy, bz + iz)) / 15.0F;
                  }
               }
            }
         }
      }

      if (state != null && state.skyRenderState != null) {
         SkyRenderState sky = state.skyRenderState;
         Vector3f direction = new Vector3f();
         CustomCelestials.setCelestialDirection(sky.sunAngle, direction);
         this.environment[4] = direction.x;
         this.environment[5] = direction.y;
         this.environment[6] = direction.z;
         this.environment[7] = sky.rainBrightness;
         CustomCelestials.setCelestialDirection(sky.moonAngle, direction);
         this.environment[8] = direction.x;
         this.environment[9] = direction.y;
         this.environment[10] = direction.z;
         this.environment[11] = sky.moonPhase == null ? 0.0F : sky.moonPhase.index();
         if (sky.skyColor != null) {
            this.environment[28] = sky.skyColor.x();
            this.environment[29] = sky.skyColor.y();
            this.environment[30] = sky.skyColor.z();
         }

         this.environment[31] = sky.starBrightness;
      }
   }

   void reset() {
      this.valid = false;
      this.frame = 0;
      this.start = System.nanoTime();
   }

   ByteBuffer diagnosticSnapshot() {
      if (!Boolean.getBoolean("sulkan.qaReadback")) throw new IllegalStateException("Diagnostic readback is disabled");
      ByteBuffer snapshot = ByteBuffer.allocate(this.bytes.capacity()).order(ByteOrder.nativeOrder());
      snapshot.put(this.bytes.duplicate().clear()).flip();
      return snapshot.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
   }

   boolean discontinuity(CameraRenderState camera, Object currentWorld, Matrix4fc view) {
      return this.globalDiscontinuity(camera, currentWorld)
         || this.projectionChanged(this.projection(camera))
         || view != null && view.m02() * this.previousView.m02() + view.m12() * this.previousView.m12() + view.m22() * this.previousView.m22() < 0.5F;
   }

   boolean globalDiscontinuity(CameraRenderState camera, Object currentWorld) {
      var qa=qaClock();
      return !this.valid
         || this.world != currentWorld
         || camera == null
         || camera.pos == null
         || Math.pow(camera.pos.x - this.x, 2.0) + Math.pow(camera.pos.y - this.y, 2.0) + Math.pow(camera.pos.z - this.z, 2.0) > 256.0
         || (qa!=null?qa.index()==0:System.nanoTime() - this.lastTime > 1000000000L);
   }

   GpuBufferSlice upload(CommandEncoder encoder, CameraRenderState camera, Matrix4fc view, int width, int height, boolean reset) {
      var qa=qaClock();
      this.frameReset = reset;
      Matrix4f projection = camera != null && camera.projectionMatrix != null ? new Matrix4f(this.projection(camera)) : new Matrix4f();
      Matrix4f model = view == null ? new Matrix4f() : new Matrix4f(view);
      if (matrixReady(projection) && matrixReady(model)) {
         projection.get(0, this.bytes);
         model.get(64, this.bytes);
         new Matrix4f(projection).invert().get(128, this.bytes);
         new Matrix4f(model).invert().get(192, this.bytes);
         (reset ? projection : this.previousProjection).get(256, this.bytes);
         (reset ? model : this.previousView).get(320, this.bytes);
         this.bytes.position(384);
         this.bytes.putFloat(!reset && camera != null ? (float)(camera.pos.x - this.x) : 0.0F);
         this.bytes.putFloat(!reset && camera != null ? (float)(camera.pos.y - this.y) : 0.0F);
         this.bytes.putFloat(!reset && camera != null ? (float)(camera.pos.z - this.z) : 0.0F);
         this.bytes.putFloat(reset ? 0.0F : 1.0F);
         long now = System.nanoTime();
         this.bytes
            .putFloat(qa!=null?qa.seconds():(float)(now - this.start) * 1.0E-9F)
            .putFloat(reset ? 0.0F : qa!=null?qa.delta():(float)(now - this.lastTime) * 1.0E-9F)
            .putFloat(qa!=null?qa.index():reset ? 0.0F : this.frame)
            .putFloat(qa!=null?0:this.partialTick);
         this.bytes.putFloat(width).putFloat(height).putFloat(1.0F / width).putFloat(1.0F / height);
         if (camera != null) {
            if (camera.pos != null) {
               this.environment[12] = (float)camera.pos.x;
               this.environment[13] = (float)camera.pos.y;
               this.environment[14] = (float)camera.pos.z;
               this.environment[16] = (float)(camera.pos.x - this.environment[12]);
               this.environment[17] = (float)(camera.pos.y - this.environment[13]);
               this.environment[18] = (float)(camera.pos.z - this.environment[14]);
            }

            float var10002;
            if (camera.fogType == null) {
               var10002 = 0.0F;
            } else {
               switch (camera.fogType) {
                  case WATER:
                     var10002 = 1.0F;
                     break;
                  case LAVA:
                     var10002 = 2.0F;
                     break;
                  case POWDER_SNOW:
                     var10002 = 3.0F;
                     break;
                  default:
                     var10002 = 0.0F;
               }
            }

            this.environment[15] = var10002;
            this.environment[19] = camera.depthFar;
            if (camera.fogData != null) {
               FogData fog = camera.fogData;
               if (fog.color != null) {
                  this.environment[20] = fog.color.x;
                  this.environment[21] = fog.color.y;
                  this.environment[22] = fog.color.z;
               }

               this.environment[23] = fog.environmentalStart;
               this.environment[24] = fog.environmentalEnd;
               this.environment[25] = fog.renderDistanceStart;
               this.environment[26] = fog.renderDistanceEnd;
               this.environment[27] = fog.skyEnd;
            }
         }

         if(qa!=null) {
            // Freeze only environment/time inputs of this already frozen QA world.
            // Camera, local sky-light interpolation and fog remain trajectory-driven.
            if(qaFixedEnvironment==null)qaFixedEnvironment=this.environment.clone();
            System.arraycopy(qaFixedEnvironment,0,this.environment,0,12);
            System.arraycopy(qaFixedEnvironment,28,this.environment,28,6);
         }
         for (float value : this.environment) {
            this.bytes.putFloat(value);
         }

         (this.inverseHandProjection == null ? new Matrix4f() : this.inverseHandProjection).get(576, this.bytes);
         this.bytes.position(640);
         this.bytes.putFloat(this.inverseHandProjection == null ? 0.0F : 1.0F).putFloat(0.0F).putFloat(0.0F).putFloat(0.0F);
         this.heldLight.write(this.bytes, camera != null && camera.pos != null ? camera.pos : Vec3.ZERO);
         this.bytes.position(1072);
         this.bytes.putFloat(this.jitterX).putFloat(this.jitterY)
            .putFloat(reset ? this.jitterX : this.previousJitterX).putFloat(reset ? this.jitterY : this.previousJitterY);
         this.bytes.putFloat(this.rayOrigin.x()).putFloat(this.rayOrigin.y()).putFloat(this.rayOrigin.z()).putFloat(0);
         Vec3 rayCamera=camera!=null && camera.pos!=null ? camera.pos : Vec3.ZERO;
         this.bytes.putFloat((float)(rayCamera.x-this.rayOrigin.x())).putFloat((float)(rayCamera.y-this.rayOrigin.y()))
            .putFloat((float)(rayCamera.z-this.rayOrigin.z())).putFloat(0);
         this.bytes.putInt((int)this.raySceneGeneration).putInt((int)(this.raySceneGeneration>>>32))
            .putInt(this.rayScenePending).putInt(qa!=null?qa.index():reset?0:this.frame);
         this.bytes.putInt(this.raySceneAvailable?1:0).putInt(emissionTriangles).putInt((int)emissionAddress).putInt((int)(emissionAddress>>>32));
         this.bytes.flip();
         return encoder.transientMemory().uploadGpu(this.bytes, RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), 128);
      } else {
         throw new IllegalArgumentException("Invalid frame matrices");
      }
   }

   void commit(CameraRenderState camera, Matrix4fc view, Object currentWorld) {
      this.previousJitterX=this.jitterX; this.previousJitterY=this.jitterY;
      if (camera != null && camera.projectionMatrix != null) {
         this.previousProjection.set(this.projection(camera));
      } else {
         this.previousProjection.identity();
      }

      if (view != null) {
         this.previousView.set(view);
      } else {
         this.previousView.identity();
      }

      if (camera != null && camera.pos != null) {
         this.x = camera.pos.x;
         this.y = camera.pos.y;
         this.z = camera.pos.z;
      }

      this.world = currentWorld;
      this.valid = true;
      this.frame = this.frameReset ? 1 : this.frame + 1;
      this.lastTime = System.nanoTime();
   }

   private boolean projectionChanged(Matrix4fc projection) {
      return projection != null
         && (
            Math.abs(projection.m00() - this.previousProjection.m00()) > Math.max(0.001F, Math.abs(this.previousProjection.m00()) * 0.05F)
               || Math.abs(projection.m11() - this.previousProjection.m11()) > Math.max(0.001F, Math.abs(this.previousProjection.m11()) * 0.05F)
         );
   }
}
