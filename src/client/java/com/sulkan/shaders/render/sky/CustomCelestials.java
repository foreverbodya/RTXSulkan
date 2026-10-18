package com.sulkan.shaders.render.sky;

import org.joml.Vector3f;

public final class CustomCelestials {
   private static final float CELESTIAL_YAW_RADIANS = (float)Math.toRadians(-45.0);
   private static final float COS_YAW = (float)Math.cos(CELESTIAL_YAW_RADIANS);
   private static final float SIN_YAW = (float)Math.sin(CELESTIAL_YAW_RADIANS);

   private CustomCelestials() {
   }

   public static void setCelestialDirection(float angleRadians, Vector3f output) {
      float horizontal = (float)Math.sin(angleRadians);
      output.set(horizontal * SIN_YAW, (float)Math.cos(angleRadians), horizontal * COS_YAW).normalize();
   }
}
