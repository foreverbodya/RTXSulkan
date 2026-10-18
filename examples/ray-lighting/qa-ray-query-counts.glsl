#ifndef SULKAN_QA_RAY_QUERY_COUNT_HELPERS
#define SULKAN_QA_RAY_QUERY_COUNT_HELPERS
// Disposable QA pack only. Private invocation counters have no atomics,
// subgroup operations or shared state. One unique image texel is written by
// the wrapper after the original entry point returns, including early returns.
// x: primary diffuse radiance, y: continuation diffuse radiance,
// z: solar visibility (all actual preflight/enumeration/any-hit initializations),
// w: emissive-triangle visibility. These count initialized queries, not BVH
// nodes, triangle tests, configured paths or rayQueryProceedEXT iterations.
uvec4 sulkanQaRayQueries=uvec4(0u);
uint sulkanQaPathRole=0u;
void sulkanQaCountRay(uint role) {
    sulkanQaRayQueries[role]++;
}
#endif
