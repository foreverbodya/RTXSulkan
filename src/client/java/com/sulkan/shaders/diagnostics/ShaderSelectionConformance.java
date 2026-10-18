package com.sulkan.shaders.diagnostics;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import com.sulkan.shaders.runtime.ShaderRuntime;
import com.sulkan.shaders.screen.ShadersScreen;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/** Exercises the real menu event route only in an explicitly marked disposable instance. */
public final class ShaderSelectionConformance {
    private static int stage;
    private static int ticks;
    private static long started;
    private static boolean finished;

    private ShaderSelectionConformance() {}

    public static void tick(Minecraft client) {
        String requested = System.getProperty("sulkan.screenConformanceDirectory");
        if (requested == null || finished) return;
        try {
            if (started != 0 && System.nanoTime() - started > 60_000_000_000L) {
                throw new IllegalStateException("Shader menu diagnostic timed out at stage " + stage);
            }
            if (client.gui.overlay() != null || ShaderRuntime.resourceReloading()) return;
            if (NativePackRuntime.failure() != null) throw new IllegalStateException(NativePackRuntime.failure());
            if (stage == 0) {
                if (!(client.gui.screen() instanceof TitleScreen)) return;
                Path directory = Path.of(requested).toRealPath();
                if (!directory.equals(client.gameDirectory.toPath().toRealPath())
                        || !Files.readString(directory.resolve("SULKAN_QA_DISPOSABLE")).trim()
                            .equals("sulkan-cinematic-disposable-v1")) {
                    throw new IllegalStateException("Menu diagnostic requires a matching marked disposable instance");
                }
                if (!ShaderRuntime.config().selectedPackId().equals("__builtin__")) {
                    throw new IllegalStateException("Menu diagnostic must start with the builtin pack");
                }
                started = System.nanoTime();
                client.setScreenAndShow(new ShadersScreen(client.gui.screen()));
                stage = 1;
            } else if (stage == 1) {
                if (++ticks < 10) return;
                ShadersScreen screen = (ShadersScreen)client.gui.screen();
                ObjectSelectionList.Entry<?> target = null;
                for (var child : screen.children()) {
                    if (child instanceof ObjectSelectionList<?> list) {
                        for (Object entry : list.children()) {
                            if (entry instanceof ObjectSelectionList.Entry<?> row
                                    && row.getNarration().getString().startsWith("RayLighting,")) target = row;
                        }
                    }
                }
                if (target == null || target.getWidth() <= 0 || target.getHeight() <= 0) {
                    throw new IllegalStateException("RayLighting menu row is missing or has no clickable bounds");
                }
                double x = target.getX() + 15;
                double y = target.getY() + target.getHeight() / 2.0;
                screen.mouseClicked(new MouseButtonEvent(x, y,
                        new MouseButtonInfo(InputConstants.MOUSE_BUTTON_RIGHT, 0)), false);
                if (!ShaderRuntime.config().selectedPackId().equals("__builtin__")) {
                    throw new IllegalStateException("Right click unexpectedly selected the pack");
                }
                if (!screen.mouseClicked(new MouseButtonEvent(x, y,
                        new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false)) {
                    throw new IllegalStateException("Screen did not consume the left click");
                }
                stage = 2;
                ticks = 0;
            } else if (stage == 2 && ++ticks >= 10) {
                if (!ShaderRuntime.config().selectedPackId().equals("RayLighting")) {
                    throw new IllegalStateException("Left click did not apply RayLighting");
                }
                LogUtils.getLogger().info("SULKAN_SHADER_SELECTION_PASS screenEventRoute=true rightClickIgnored=true leftClickApplied=true reloadCompleted=true pack=RayLighting");
                finished = true;
                client.stop();
            }
        } catch (Throwable failure) {
            LogUtils.getLogger().error("SULKAN_SHADER_SELECTION_FAIL", failure);
            finished = true;
            client.stop();
        }
    }
}
