package com.sulkan.shaders.diagnostics;

import com.sulkan.shaders.runtime.NativeShaderCompiler;
import com.mojang.renderpearl.util.ShaderCompileException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CheckNativeShaderSourceSize {
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]);if(Files.exists(out))throw new IllegalArgumentException("New report required");
        String padding="// UTF-8 исходник ☀: preserved in debug information\n".repeat(20000);
        String valid="#version 460\n"+padding+"layout(local_size_x=1) in; void main(){}\n";
        int invalidRejected=0,modulesClosed=0;long spirvBytes=0;
        var compiler=new NativeShaderCompiler();
        try(compiler) {
            for(int i=0;i<4;i++) {
                try(var module=compiler.compileCompute("large-utf8-"+i,valid)) {
                    spirvBytes+=module.code().remaining();
                }
                modulesClosed++;
                try(var unexpected=compiler.compileCompute("invalid-large-"+i,"#version 460\n"+padding+"not_valid_glsl\n")) {
                    throw new IllegalStateException("Invalid GLSL compiled");
                }catch(ShaderCompileException expected){invalidRejected++;}
            }
            try(var module=compiler.compileCompute("valid-after-errors","#version 460\nlayout(local_size_x=1) in; void main(){}")) {
                if(module.code().remaining()==0)throw new IllegalStateException("Empty module after compile errors");
            }
        }
        boolean closedRejected=false;
        try{compiler.compileCompute("closed",valid);}catch(IllegalStateException expected){closedRejected=true;}
        if(invalidRejected!=4 || !closedRejected)throw new IllegalStateException("Compiler lifecycle failed");
        var report=java.util.Map.of("passed",true,"sourceUtf8Bytes",valid.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
            "largeModulesClosed",modulesClosed,"invalidCompilationsRejected",invalidRejected,"closedCompilerRejected",closedRejected,
            "totalSpirvBytes",spirvBytes,"scope","Actual native shaderc calls under default LWJGL stack size, large UTF-8 compute source, compiler error and subsequent valid compile/lifecycle; not a GPU draw or memory leak proof.");
        Files.writeString(out,new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));System.out.println("NATIVE_SHADER_SOURCE_SIZE_PASS "+report);
    }
}
