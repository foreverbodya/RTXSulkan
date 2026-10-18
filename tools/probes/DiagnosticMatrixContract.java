import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;

/** Regression for Java-owned snapshots: never hand a heap FloatBuffer to JOML Unsafe. */
public final class DiagnosticMatrixContract {
    public static void main(String[] args) throws Exception {
        var type = Class.forName("com.sulkan.shaders.diagnostics.DeferredLightingConformance");
        var matrix = type.getDeclaredMethod("matrix", ByteBuffer.class, int.class);
        matrix.setAccessible(true);
        ByteBuffer[] buffers = {ByteBuffer.allocate(256).order(ByteOrder.nativeOrder()),
                ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder()),
                ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN)};
        for (ByteBuffer source : buffers) {
            for (int i = 0; i < 16; i++) source.putFloat(128 + i * 4, i + .25f);
            for (ByteBuffer input : new ByteBuffer[]{source, source.asReadOnlyBuffer().order(source.order())}) {
                Matrix4f result = (Matrix4f) matrix.invoke(null, input, 128);
                for (int column = 0; column < 4; column++) for (int row = 0; row < 4; row++) {
                    if (result.get(column, row) != column * 4 + row + .25f) throw new AssertionError("Matrix layout differs");
                }
            }
        }
        System.out.println("SULKAN_DIAGNOSTIC_MATRIX_PASS buffers=6 nativeHeap=true direct=true otherEndian=true readOnly=true");
    }
}
