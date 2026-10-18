package com.sulkan.shaders.diagnostics;

import com.mojang.logging.LogUtils;
import com.sulkan.shaders.graph.NativePackRuntime;
import com.sulkan.shaders.runtime.ShaderRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.item.Items;

/** Capture-only reproduction, entered after QaWorldValidation verifies its disposable world. */
final class QaHeldItemCapture {
    private static final String[] BACKGROUNDS={"stone","oak_leaves[persistent=true]","water"};
    private static int phase,ticks;
    private static boolean pending,configured;

    static void tick(Minecraft client,Path evidence) throws Exception {
        client.player.setNoGravity(true);
        if(pending)return;
        if(!configured) {
            pending=true;
            var server=client.getSingleplayerServer();
            server.execute(()->{
                var source=server.createCommandSourceStack().withSuppressedOutput();
                server.getPlayerList().getPlayers().getFirst().setNoGravity(true);
                for(String command:new String[]{"gamemode creative @p","clear @p",
                    "fill 0 96 0 23 96 23 minecraft:"+BACKGROUNDS[phase/2],
                    "tp @p 12.0 101.0 12.0 0 65"}) server.getCommands().performPrefixedCommand(source,command);
                if(phase%2==1)server.getCommands().performPrefixedCommand(source,"item replace entity @p weapon.mainhand with minecraft:compass");
                var player=server.getPlayerList().getPlayers().getFirst();
                player.getAbilities().flying=true;
                player.onUpdateAbilities();
                client.execute(()->{configured=true;ticks=0;pending=false;});
            });
            return;
        }
        if(++ticks<100 || NativePackRuntime.renderedFrames()<100)return;
        boolean held=phase%2==1;
        if(held?!client.player.getMainHandItem().is(Items.COMPASS):!client.player.getMainHandItem().isEmpty())
            throw new IllegalStateException("Held-item fixture inventory did not synchronize");
        if(Math.abs(client.player.getX()-12)>.01 || Math.abs(client.player.getY()-101)>.01 || Math.abs(client.player.getZ()-12)>.01
            || Math.abs(client.player.getXRot()-65)>.01 || Math.abs(client.player.getYRot())>.01)
            throw new IllegalStateException("Held-item fixture camera moved: "+client.player.position()+" yaw="+client.player.getYRot()+" pitch="+client.player.getXRot()+" flying="+client.player.getAbilities().flying);
        String name="held-item-"+BACKGROUNDS[phase/2].split("\\[")[0]+(held?"-compass":"-empty");
        var report=new LinkedHashMap<String,Object>();
        report.put("pack",ShaderRuntime.config().selectedPackId());report.put("compass",held);
        report.put("background",BACKGROUNDS[phase/2]);report.put("feet",java.util.List.of(client.player.getX(),client.player.getY(),client.player.getZ()));
        report.put("yaw",client.player.getYRot());report.put("pitch",client.player.getXRot());
        report.put("scope","Capture only; no automatic visibility or visual-quality verdict");
        Files.writeString(evidence.resolve(name+".json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
        pending=true;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
            try(image){image.writeToFile(evidence.resolve(name+".png"));}
            catch(Exception error){LogUtils.getLogger().error("SULKAN_QA_HELD_ITEM_CAPTURE_FAIL",error);client.execute(client::stop);return;}
            client.execute(()->{
                if(++phase==6){LogUtils.getLogger().info("SULKAN_QA_HELD_ITEM_CAPTURE_COMPLETE captures=6 visualAcceptance=false");client.stop();}
                else {configured=false;pending=false;}
            });
        });
    }
}
