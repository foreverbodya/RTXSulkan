package com.sulkan.shaders.graph;

import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

public final class MaterialEncodingContract {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var a=MaterialTable.compile(Map.of("minecraft:stone",1000,"minecraft:oak_leaves",5));
        var b=MaterialTable.compile(Map.of("minecraft:stone",1000,"minecraft:oak_leaves",5));
        require(a.sameVertexEncoding(b),"Equivalent independently compiled IDs differ");
        require(b.sameVertexEncoding(a),"Encoding equality is asymmetric");
        require(!a.sameVertexEncoding(MaterialTable.compile(Map.of("minecraft:stone",1001,"minecraft:oak_leaves",5))),"Changed ID reused");
        require(!a.sameVertexEncoding(MaterialTable.compile(Map.of("minecraft:stone",1000))),"Removed selector reused");
        a.vegetationWind=true;
        require(!a.sameVertexEncoding(b),"Changed wind encoding reused");
        b.vegetationWind=true;
        require(a.sameVertexEncoding(b),"Matching wind encoding differs");
        var empty=MaterialTable.compile(Map.of());
        require(empty.sameVertexEncoding(null),"Absent and disabled material encodings differ");
        require(empty.sameVertexEncoding(MaterialTable.compile(Map.of())),"Disabled encodings differ");
        require(!a.sameVertexEncoding(null) && !a.sameVertexEncoding(empty) && !empty.sameVertexEncoding(a),"Enabled/disabled vertex formats reused");
        require(!empty.sameVertexEncoding(MaterialTable.compile(Map.of("minecraft:stone",0))),"Explicit ID-zero material format treated as disabled");
        System.out.println("SULKAN_MATERIAL_ENCODING_CONTRACT_PASS equivalentIds=true changedIds=true removedSelector=true wind=true enabledFormat=true largeIds=true scope=CPU_ONLY");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
