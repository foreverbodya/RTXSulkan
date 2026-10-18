import com.sulkan.shaders.render.raytracing.RaySurfaceAttributes;
import com.sulkan.shaders.render.raytracing.TerrainMesh;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class RaySurfaceContract {
    public static void main(String[] args) {
        for (int stride : new int[]{20,24,32}) {
            ByteBuffer data = quad(stride);
            TerrainMesh mesh = TerrainMesh.copyCompressedQuads(data, 4, TerrainMesh.Opacity.CUTOUT);
            data.clear();
            while (data.hasRemaining()) data.put((byte)0x55);
            var surface = RaySurfaceAttributes.interpolate(mesh, 0, .25f, .25f, .01f, .02f);
            close(surface.position().x(), 1); close(surface.position().y(), .5f); close(surface.position().z(), 0);
            close(surface.normal().z(), 1); close(surface.normal().x(), 0); close(surface.normal().y(), 0);
            close(surface.atlasUV().x(), .25f); close(surface.atlasUV().y(), .3025f);
            close(surface.tint().r(), .5f); close(surface.tint().g(), .25f); close(surface.tint().b(), .25f); close(surface.tint().a(), 1);
            close(surface.blockLight(), .375f); close(surface.skyLight(), .625f);
            if (surface.materialClass() != (stride >= 24 ? 17 : 0) || surface.terrainFlags() != 3) throw new AssertionError("Material/flag decode differs");
            close(surface.windWeight(), stride >= 24 ? (32768f / 65535f + 1) * .25f : 0);
            close(surface.occlusion(),stride==32 ? .35f : 1);
            var second = RaySurfaceAttributes.interpolate(mesh, 1, .25f, .25f, 0, 0);
            close(second.position().x(), 1); close(second.position().y(), 1.5f);
            ByteBuffer packed = RaySurfaceAttributes.serialize(mesh);
            if (!packed.isReadOnly() || packed.remaining() != 168 || packed.getInt(0) != 4 || packed.getInt(4) != 2
                    || packed.getInt(8) != 1 || packed.getInt(12) != 32 || packed.getInt(32) != 0xff0000ff) {
                throw new AssertionError("GPU payload header/size/color differs");
            }
            close(packed.getFloat(28),stride==32 ? .2f : 1);
            int[] order = {0,1,2,2,3,0};
            for (int i = 0; i < order.length; i++) if (packed.getInt(144 + i * 4) != order[i]) throw new AssertionError("GPU triangle order differs");
            ByteBuffer compact = RaySurfaceAttributes.serializeCompact(mesh);
            if (!compact.isReadOnly() || compact.remaining()!=128 || compact.getInt(0)!=4 || compact.getInt(4)!=2
                    || compact.getInt(8)!=1 || compact.getInt(12)!=28) throw new AssertionError("Compact header/extent differs");
            var words=mesh.packedVertices(); int inputWords=stride/4;
            for(int vertex=0;vertex<4;vertex++) {
                int offset=16+vertex*28;
                if(compact.getInt(offset)!=words.get(vertex*inputWords) || compact.getInt(offset+4)!=words.get(vertex*inputWords+1)
                    || compact.getInt(offset+8)!=mesh.packedBaseColor(vertex) || compact.getInt(offset+12)!=mesh.packedTexture(vertex)
                    || compact.getInt(offset+16)!=mesh.packedLightAndData(vertex) || compact.getInt(offset+20)!=mesh.packedMaterial(vertex)
                    || Float.floatToRawIntBits(compact.getFloat(offset+24))!=Float.floatToRawIntBits(mesh.bakedOcclusion(vertex)))
                    throw new AssertionError("Compact vertex bits differ");
            }
            rejected(() -> RaySurfaceAttributes.interpolate(mesh, 0, Float.NaN, 0, 0, 0));
            rejected(() -> RaySurfaceAttributes.interpolate(mesh, 0, .6f, .6f, 0, 0));
            rejected(() -> RaySurfaceAttributes.interpolate(mesh, 0, 0, 0, -1, 0));
        }
        ByteBuffer invalid=quad(32);invalid.putFloat(28,Float.NaN);
        rejected(() -> TerrainMesh.copyCompressedQuads(invalid,4,TerrainMesh.Opacity.OPAQUE));
        System.out.println("SULKAN_RAY_SURFACE_CONTRACT_PASS strides=20,24,32 triangles=2 barycentrics=true atlasBias=true colors=true occlusion=true lights=true variableWind=true payload=true ownedLifetime=true invalidInputsRejected=true scope=CPU_ONLY");
    }
    private static ByteBuffer quad(int stride) {
        ByteBuffer data = ByteBuffer.allocate(stride * 4).order(ByteOrder.nativeOrder());
        int[][] position = {{0,0,0},{2,0,0},{2,2,0},{0,2,0}};
        int[] color = {0xff0000ff,0xff00ff00,0xffff0000,0xffffffff};
        int[] uv = {(4096|32768)|(8192<<16),12288|(8192<<16),12288|((16384|32768)<<16),(4096|32768)|((16384|32768)<<16)};
        int[] block = {0,120,240,0}, sky = {240,120,0,240}, wind = {0,32768,65535,0};
        for (int i = 0; i < 4; i++) {
            int high = 0, low = 0;
            for (int axis = 0; axis < 3; axis++) {
                int coordinate = (position[i][axis] + 8) * 32768;
                high |= (coordinate >>> 10) << (axis * 10);
                low |= (coordinate & 1023) << (axis * 10);
            }
            data.putInt(high).putInt(low).putInt(stride==32 ? 0xff000000 : color[i]).putInt(uv[i]).putInt(block[i]|(sky[i]<<8)|(3<<16)|(4<<24));
            if (stride >= 24) data.putInt(17|(wind[i]<<16));
            if (stride == 32) data.putInt(color[i]).putFloat((i+1)*.2f);
        }
        return data.flip();
    }
    private static void close(float actual, float expected) {
        if (!Float.isFinite(actual) || Math.abs(actual-expected) > .00001f) throw new AssertionError("Expected " + expected + " got " + actual);
    }
    private static void rejected(Runnable operation) {
        try { operation.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid input accepted");
    }
}
