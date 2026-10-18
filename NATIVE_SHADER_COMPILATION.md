# Expanded shader source allocation

`qa-leaf-optics-comparison-1` is retained as a startup failure. Shaderc's LWJGL
`CharSequence` overload encodes the complete expanded GLSL source on the small
thread-local native stack. The failing stack trace reaches MemoryStack.nmalloc
from Shaderc.shaderc_compile_into_spv and NativeShaderCompiler.compileCompute.
This is source encoding allocation, not exhaustion of the Java call stack or
evidence of a GPU ray-query failure.

NativeShaderCompiler now encodes source, name and entry point in explicit native
allocations and calls shaderc's ByteBuffer overload. All three allocations are
freed in finally, including partial-allocation and compilation failures. Shaderc
result ownership, optimization settings and SPIR-V module ownership are unchanged.
Vertex/fragment and compute compilation share this source-allocation helper.

CheckNativeShaderSourceSize invokes the actual native shaderc with a 1,240,054
byte UTF-8 compute source, four successful large modules, four invalid large
sources, then a valid small source and the existing closed-compiler rejection.
It passes under default LWJGL stack settings. Against the exact retained
pre-fix JAR the same probe reproduces OutOfMemoryError: Out of stack space.
See native-shader-source-size-1.json and native-shader-source-size-legacy-control-1.json.
This covers compilation/error/lifecycle execution, not a native memory leak proof.
The subsequent natural-world comparison loads and renders the formerly failing
shader configuration; its visual/material validation has separate evidence.
