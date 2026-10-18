Per-pass A/B/A means in ms. AS/upload rows are amortized event totals. World total is nested; do not sum rows.

## qa-profile-canonical-sameworld-1 / static

| GPU interval | Before ms | After ms | Saved ms |
| --- | ---: | ---: | ---: |
| world.total | 116.1916 | 112.1201 | 4.0715 |
| indirect.trace | 34.5817 | 34.5224 | 0.0593 |
| cloud.view | 25.4825 | 25.4714 | 0.0110 |
| cloud.environment | 19.3312 | 15.3050 | 4.0262 |
| leaf.indirect.trace | 15.9569 | 15.9872 | -0.0302 |
| cloud.composite | 10.3738 | 10.3218 | 0.0521 |
| rough.reflection | 3.7836 | 3.7683 | 0.0154 |
| atmosphere.multiple | 1.6201 | 1.6259 | -0.0058 |
| native.dlss | 1.0503 | 1.0514 | -0.0011 |
| cloud.shadow | 0.7017 | 0.6954 | 0.0062 |
| ray.direct | 0.3201 | 0.3194 | 0.0008 |
| cloud.shadow.diagnostic | 0.2753 | 0.2770 | -0.0017 |
| rough.prefilter.1 | 0.1610 | 0.1611 | -0.0001 |
| rough.prefilter.2 | 0.1512 | 0.1511 | 0.0001 |
| water.trace | 0.1448 | 0.1459 | -0.0011 |
| indirect.spatial.4 | 0.1292 | 0.1291 | 0.0000 |
| indirect.spatial.1 | 0.1288 | 0.1285 | 0.0003 |
| indirect.spatial.2 | 0.1269 | 0.1270 | -0.0001 |
| cloud.reconstruction | 0.1230 | 0.1232 | -0.0002 |
| cloud.lighting | 0.1227 | 0.1370 | -0.0143 |
| atmosphere.lighting | 0.1221 | 0.1206 | 0.0015 |
| rough.temporal | 0.1105 | 0.1100 | 0.0005 |
| display.histogram | 0.0919 | 0.0921 | -0.0002 |
| indirect.temporal | 0.0909 | 0.0910 | -0.0001 |
| atmosphere.sky | 0.0728 | 0.0738 | -0.0010 |
| leaf.indirect.spatial.1 | 0.0717 | 0.0717 | -0.0000 |
| leaf.indirect.spatial.4 | 0.0715 | 0.0714 | 0.0001 |
| leaf.indirect.spatial.2 | 0.0699 | 0.0711 | -0.0012 |
| cloud.ambient.diagnostic | 0.0674 | 0.0708 | -0.0034 |
| composition.present | 0.0548 | 0.0548 | -0.0000 |
| distant.world | 0.0501 | 0.0508 | -0.0007 |
| leaf.indirect.temporal | 0.0432 | 0.0433 | -0.0001 |
| display.exposure | 0.0388 | 0.0389 | -0.0001 |
| motion.camera | 0.0378 | 0.0389 | -0.0011 |
| ray.reflection | 0.0348 | 0.0349 | -0.0000 |
| rough.composite | 0.0286 | 0.0287 | -0.0001 |
| ray.composite | 0.0204 | 0.0204 | 0.0000 |
| reconstruction.inputs | 0.0204 | 0.0204 | -0.0000 |
| water.composite | 0.0204 | 0.0204 | 0.0000 |
| cloud.scattering.diagnostic | 0.0124 | 0.0126 | -0.0001 |
| display.meter | 0.0120 | 0.0121 | -0.0001 |
| indirect.state | 0.0069 | 0.0069 | -0.0000 |
| indirect.environment | 0.0064 | 0.0063 | 0.0001 |
| reconstruction.exposure | 0.0059 | 0.0058 | 0.0002 |
| blas.build | 0.0000 | 0.0000 | 0.0000 |
| cloud.exact.noise | 0.0000 | 0.0403 | -0.0403 |
| geometry.upload | 0.0000 | 0.0000 | 0.0000 |
| tlas.build | 0.0000 | 0.0000 | 0.0000 |

## qa-profile-canonical-sameworld-1 / motion

| GPU interval | Before ms | After ms | Saved ms |
| --- | ---: | ---: | ---: |
| world.total | 116.5544 | 112.3468 | 4.2076 |
| indirect.trace | 34.0237 | 33.9709 | 0.0528 |
| cloud.view | 25.1527 | 25.1070 | 0.0457 |
| cloud.environment | 19.3120 | 15.3686 | 3.9434 |
| leaf.indirect.trace | 16.5371 | 16.3978 | 0.1394 |
| cloud.composite | 10.7386 | 10.6938 | 0.0448 |
| rough.reflection | 4.0760 | 4.0410 | 0.0349 |
| atmosphere.multiple | 1.6373 | 1.6322 | 0.0051 |
| native.dlss | 1.0546 | 1.0541 | 0.0006 |
| cloud.shadow | 0.7045 | 0.7042 | 0.0004 |
| ray.direct | 0.3233 | 0.3219 | 0.0014 |
| cloud.shadow.diagnostic | 0.2746 | 0.2764 | -0.0018 |
| rough.prefilter.1 | 0.1602 | 0.1603 | -0.0002 |
| rough.prefilter.2 | 0.1500 | 0.1501 | -0.0001 |
| water.trace | 0.1458 | 0.1458 | -0.0000 |
| indirect.spatial.4 | 0.1285 | 0.1284 | 0.0001 |
| indirect.spatial.1 | 0.1280 | 0.1280 | -0.0001 |
| indirect.spatial.2 | 0.1262 | 0.1261 | 0.0001 |
| atmosphere.lighting | 0.1217 | 0.1229 | -0.0012 |
| cloud.lighting | 0.1204 | 0.1372 | -0.0168 |
| cloud.reconstruction | 0.1192 | 0.1193 | -0.0001 |
| rough.temporal | 0.1105 | 0.1107 | -0.0003 |
| display.histogram | 0.0915 | 0.0917 | -0.0002 |
| indirect.temporal | 0.0904 | 0.0904 | 0.0000 |
| leaf.indirect.spatial.1 | 0.0743 | 0.0744 | -0.0001 |
| atmosphere.sky | 0.0739 | 0.0750 | -0.0011 |
| leaf.indirect.spatial.4 | 0.0733 | 0.0733 | 0.0000 |
| leaf.indirect.spatial.2 | 0.0721 | 0.0721 | 0.0000 |
| cloud.ambient.diagnostic | 0.0669 | 0.0666 | 0.0003 |
| composition.present | 0.0547 | 0.0547 | -0.0000 |
| distant.world | 0.0509 | 0.0507 | 0.0002 |
| leaf.indirect.temporal | 0.0448 | 0.0451 | -0.0002 |
| motion.camera | 0.0384 | 0.0389 | -0.0005 |
| display.exposure | 0.0383 | 0.0389 | -0.0006 |
| ray.reflection | 0.0349 | 0.0349 | 0.0000 |
| rough.composite | 0.0286 | 0.0287 | -0.0001 |
| reconstruction.inputs | 0.0204 | 0.0204 | 0.0000 |
| ray.composite | 0.0204 | 0.0204 | 0.0000 |
| water.composite | 0.0204 | 0.0204 | -0.0000 |
| cloud.scattering.diagnostic | 0.0127 | 0.0128 | -0.0002 |
| display.meter | 0.0121 | 0.0120 | 0.0001 |
| indirect.state | 0.0068 | 0.0068 | 0.0000 |
| indirect.environment | 0.0064 | 0.0063 | 0.0001 |
| reconstruction.exposure | 0.0058 | 0.0057 | 0.0001 |
| blas.build | 0.0000 | 0.0000 | 0.0000 |
| cloud.exact.noise | 0.0000 | 0.0376 | -0.0376 |
| geometry.upload | 0.0000 | 0.0000 | 0.0000 |
| tlas.build | 0.0000 | 0.0000 | 0.0000 |

## qa-profile-cloud-env-perf-1 / static

| GPU interval | Before ms | After ms | Saved ms |
| --- | ---: | ---: | ---: |
| world.total | 115.7687 | 112.2012 | 3.5675 |
| indirect.trace | 34.2053 | 34.2201 | -0.0149 |
| cloud.view | 25.8279 | 26.1407 | -0.3128 |
| cloud.environment | 19.2815 | 15.2936 | 3.9878 |
| leaf.indirect.trace | 15.5384 | 15.5533 | -0.0149 |
| cloud.composite | 10.4297 | 10.4768 | -0.0471 |
| rough.reflection | 3.7397 | 3.7453 | -0.0056 |
| atmosphere.multiple | 1.6361 | 1.6336 | 0.0025 |
| native.dlss | 1.0504 | 1.0491 | 0.0013 |
| cloud.shadow | 0.7172 | 0.7235 | -0.0064 |
| ray.direct | 0.3182 | 0.3189 | -0.0007 |
| cloud.shadow.diagnostic | 0.2760 | 0.2764 | -0.0004 |
| rough.prefilter.1 | 0.1601 | 0.1602 | -0.0001 |
| rough.prefilter.2 | 0.1498 | 0.1499 | -0.0001 |
| water.trace | 0.1439 | 0.1438 | 0.0001 |
| indirect.spatial.4 | 0.1290 | 0.1290 | -0.0000 |
| indirect.spatial.1 | 0.1283 | 0.1297 | -0.0014 |
| indirect.spatial.2 | 0.1265 | 0.1276 | -0.0012 |
| cloud.reconstruction | 0.1226 | 0.1226 | 0.0000 |
| atmosphere.lighting | 0.1213 | 0.1193 | 0.0020 |
| cloud.lighting | 0.1202 | 0.1409 | -0.0207 |
| rough.temporal | 0.1096 | 0.1095 | 0.0000 |
| display.histogram | 0.0921 | 0.0921 | 0.0000 |
| indirect.temporal | 0.0906 | 0.0906 | -0.0000 |
| atmosphere.sky | 0.0722 | 0.0747 | -0.0026 |
| leaf.indirect.spatial.1 | 0.0716 | 0.0716 | -0.0000 |
| leaf.indirect.spatial.4 | 0.0713 | 0.0712 | 0.0001 |
| leaf.indirect.spatial.2 | 0.0702 | 0.0696 | 0.0006 |
| cloud.ambient.diagnostic | 0.0683 | 0.0667 | 0.0016 |
| composition.present | 0.0549 | 0.0549 | 0.0000 |
| distant.world | 0.0501 | 0.0501 | -0.0000 |
| leaf.indirect.temporal | 0.0431 | 0.0430 | 0.0001 |
| display.exposure | 0.0388 | 0.0388 | 0.0000 |
| motion.camera | 0.0388 | 0.0387 | 0.0001 |
| cloud.exact.noise | 0.0369 | 0.0379 | -0.0010 |
| ray.reflection | 0.0349 | 0.0349 | -0.0000 |
| rough.composite | 0.0286 | 0.0286 | 0.0000 |
| reconstruction.inputs | 0.0204 | 0.0204 | -0.0000 |
| ray.composite | 0.0204 | 0.0204 | -0.0000 |
| water.composite | 0.0204 | 0.0204 | -0.0000 |
| cloud.scattering.diagnostic | 0.0125 | 0.0126 | -0.0001 |
| display.meter | 0.0121 | 0.0121 | -0.0000 |
| indirect.state | 0.0068 | 0.0068 | -0.0000 |
| indirect.environment | 0.0064 | 0.0063 | 0.0001 |
| reconstruction.exposure | 0.0058 | 0.0058 | -0.0001 |
| blas.build | 0.0000 | 0.0000 | 0.0000 |
| geometry.upload | 0.0000 | 0.0000 | 0.0000 |
| tlas.build | 0.0000 | 0.0000 | 0.0000 |

## qa-profile-cloud-env-perf-1 / motion

| GPU interval | Before ms | After ms | Saved ms |
| --- | ---: | ---: | ---: |
| world.total | 115.8050 | 111.4274 | 4.3775 |
| indirect.trace | 33.6245 | 33.5313 | 0.0932 |
| cloud.view | 25.6520 | 25.4245 | 0.2275 |
| cloud.environment | 19.2783 | 15.3295 | 3.9488 |
| leaf.indirect.trace | 15.8639 | 15.8592 | 0.0048 |
| cloud.composite | 10.6781 | 10.5379 | 0.1402 |
| rough.reflection | 3.9881 | 3.9887 | -0.0005 |
| atmosphere.multiple | 1.6253 | 1.6347 | -0.0094 |
| native.dlss | 1.0514 | 1.0530 | -0.0015 |
| cloud.shadow | 0.7013 | 0.7038 | -0.0026 |
| ray.direct | 0.3196 | 0.3186 | 0.0010 |
| cloud.shadow.diagnostic | 0.2761 | 0.2763 | -0.0002 |
| rough.prefilter.1 | 0.1589 | 0.1589 | -0.0001 |
| rough.prefilter.2 | 0.1486 | 0.1486 | -0.0000 |
| water.trace | 0.1429 | 0.1427 | 0.0002 |
| indirect.spatial.4 | 0.1279 | 0.1275 | 0.0005 |
| indirect.spatial.1 | 0.1268 | 0.1268 | 0.0000 |
| indirect.spatial.2 | 0.1252 | 0.1251 | 0.0001 |
| atmosphere.lighting | 0.1223 | 0.1223 | -0.0001 |
| cloud.lighting | 0.1212 | 0.1411 | -0.0199 |
| cloud.reconstruction | 0.1186 | 0.1186 | 0.0000 |
| rough.temporal | 0.1094 | 0.1093 | 0.0001 |
| display.histogram | 0.0918 | 0.0918 | -0.0000 |
| indirect.temporal | 0.0901 | 0.0898 | 0.0002 |
| atmosphere.sky | 0.0747 | 0.0758 | -0.0011 |
| leaf.indirect.spatial.1 | 0.0734 | 0.0734 | 0.0000 |
| leaf.indirect.spatial.4 | 0.0723 | 0.0723 | -0.0000 |
| leaf.indirect.spatial.2 | 0.0712 | 0.0713 | -0.0000 |
| cloud.ambient.diagnostic | 0.0676 | 0.0678 | -0.0002 |
| composition.present | 0.0547 | 0.0547 | -0.0000 |
| distant.world | 0.0505 | 0.0506 | -0.0001 |
| leaf.indirect.temporal | 0.0438 | 0.0438 | -0.0000 |
| display.exposure | 0.0389 | 0.0388 | 0.0000 |
| motion.camera | 0.0386 | 0.0385 | 0.0001 |
| cloud.exact.noise | 0.0374 | 0.0380 | -0.0006 |
| ray.reflection | 0.0347 | 0.0347 | 0.0001 |
| rough.composite | 0.0283 | 0.0283 | -0.0000 |
| reconstruction.inputs | 0.0204 | 0.0204 | -0.0000 |
| ray.composite | 0.0204 | 0.0204 | 0.0000 |
| water.composite | 0.0204 | 0.0203 | 0.0000 |
| cloud.scattering.diagnostic | 0.0132 | 0.0125 | 0.0007 |
| display.meter | 0.0120 | 0.0121 | -0.0001 |
| indirect.state | 0.0068 | 0.0068 | 0.0000 |
| indirect.environment | 0.0063 | 0.0063 | 0.0001 |
| reconstruction.exposure | 0.0056 | 0.0056 | -0.0000 |
| blas.build | 0.0000 | 0.0000 | 0.0000 |
| geometry.upload | 0.0000 | 0.0000 | 0.0000 |
| tlas.build | 0.0000 | 0.0000 | 0.0000 |

## canonical-three-process-bracket / static

Invalid comparison: geometry differs. Raw phase measurements remain in passes.csv.

## canonical-three-process-bracket / motion

Invalid comparison: geometry differs. Raw phase measurements remain in passes.csv.
