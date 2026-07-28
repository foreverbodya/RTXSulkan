# Sulkan

Sulkan is a client-side Fabric shader mod for Minecraft's Vulkan renderer. It uses Sodium as its rendering and optimization foundation.

## Features

* Shader loading support for the Vulkan renderer.
* Sodium-powered rendering optimizations.

## Requirements

* Minecraft 26.2
* Fabric Loader 0.18.4 or newer
* Fabric API 0.155.0 or newer for Minecraft 26.2
* Sodium 0.9.1 or newer for Minecraft 26.2

## Links

* **Website**: [sulkan.org](https://sulkan.org)
* **Community**: [Discord](https://discord.gg/9wXEBAmDNS)

## Building

To build the project, run:
```bash
./gradlew build
```
The build produces the distributable Sulkan JAR under `build/libs/`. Sodium remains a separate required mod and is not bundled into the JAR.

## License

This project is licensed under the **GNU General Public License v3.0** (GPLv3) - see the [LICENSE](LICENSE) file for details.
