# Neo-Voxy REFORGED

<a href="https://discord.gg/5hAxFamnpf"><img src="https://img.shields.io/badge/Discord-5865F2?logo=discord&logoColor=white&style=flat-square" height="18" alt="Discord"></a>
<a href="https://minecraft.net/"><img src="https://img.shields.io/badge/Minecraft-1.21.1-brightgreen?logo=minecraft&logoColor=white&style=flat-square" height="18" alt="Minecraft"></a>


> **Neo-Voxy** is an unofficial community port and enhancement of the [Voxy](https://github.com/cortex/voxy) mod by **cortex**, bringing massive-distance Level of Detail (LOD) voxel rendering natively to **NeoForge 1.21.1**.

Join our community discord: **[https://discord.gg/5hAxFamnpf](https://discord.gg/5hAxFamnpf)**

---

## Key Features

- **Massive LOD Render Distances:** Render thousands of blocks of terrain with GPU-driven geometry pipelines, frustum culling, and efficient voxel compression.
- **Native NeoForge 1.21.1 Support:** Complete lifecycle and registry integration with NeoForge's FML and ModLauncher pipeline.
- **Chunky Pre-Generation Integration:** Seamless background ingestion during Chunky world pre-generation runs via custom `MixinNeoForgeWorld` hooks.
- **Iris Shader Compatibility:** Full support for Voxy-compatible shader packs using Iris on NeoForge.
- **Sodium NeoForge Pipeline:** High-performance integration with Sodium 0.6.0+ for NeoForge.
- **Dynamic Chunk Streaming & Networking:** Client-server LOD streaming architecture with custom bandwidth limiting and congestion control.
- **Multi-Language Localizations:** Comprehensive translations for **English**, **French**, **German**, **Spanish**, **Russian**, **Japanese**, **Brazilian Portuguese**, **Simplified Chinese**, and **Traditional Chinese**.

---

## Legal Distribution Architecture (ARR-Safe)

The original Voxy mod is licensed under **All Rights Reserved (ARR)** by **cortex**. To respect copyright law and maintain 100% legal compliance without distributing original or modified Voxy bytecode, Neo-Voxy utilizes a **Differential Build & Runtime Patcher Architecture**:

```
+-------------------------------------------------------------+
|              neovoxy-0.6.1-distributable.jar                 |
|  - Zero raw original Voxy .class files                      |
|  - Zero raw modified Voxy .class files                      |
|  - IDependencyLocator Runtime Bootstrap                     |
|  - Pure XOR Binary Delta Patches (voxy_patches/*.delta)     |
|  - Additions Pack (voxy_patches/voxy_additions.pack)        |
+-------------------------------------------------------------+
                              |
                     Game Boot (Client Launch)
                              |
       +----------------------+----------------------+
       |                                             |
[Checks user's mods/ folder]         [Missing? Downloads official JAR]
       |                                             |
       +----------------------+----------------------+
                              |
                              v
             Official Fabric Voxy 0.2.20-beta JAR
            (Retrieved directly from Modrinth CDN)
                              |
                              v
             In-Memory Delta Reconstruction (XOR)
           + Additions Extraction (Bridges & Libs)
                              |
                              v
                voxy_neoforge_patched.jar
           (Cached in .minecraft/cache/ directory)
                              |
                              v
        Direct NeoForge FML Module Registration & Boot
```

- **Zero Copyrighted Code in Distributable Builds:** The distributed `.jar` contains only original loader classes, open-source libraries (RocksDB JNI, Jedis, Zstandard, XZ, Commons-Pool), and mathematically inert XOR binary delta patches.
- **100% Original Author Attribution:** The runtime patcher fetches the official Fabric Voxy JAR directly from Cortex's official [Modrinth project](https://modrinth.com/mod/voxy), ensuring the original creator receives all download metrics and attribution.
- **Automated Verification:** The Gradle build enforces strict legal verification—any attempt to package raw Voxy classes will fail the build with a `GradleException`.

---

## Requirements

| Dependency | Required Version |
| :--- | :--- |
| **Minecraft** | `1.21.1` |
| **NeoForge** | `21.1.77` or newer |
| **Sodium (NeoForge)** | `0.6.0+` |
| **Iris (NeoForge)** | Latest compatible build for 1.21.1 |

> ⚠️ **Incompatibility Notice:** **BetterFpsDist** is currently incompatible with Neo-Voxy.

---

## Installation

1. Make sure you have **NeoForge 21.1.77+** installed for Minecraft 1.21.1.
2. Ensure **Sodium** and **Iris** (NeoForge editions) are present in your `mods` folder.
3. Download the latest `neovoxy-0.6.1-distributable.jar` from the [Releases](https://github.com/meansabine/neo-voxy/releases) page and place it into your `.minecraft/mods` directory.
4. Launch the game! On first launch, Neo-Voxy will automatically fetch the official Fabric Voxy JAR from Modrinth and prepare the cached runtime mod.

---

## Building from Source

### Prerequisites
- JDK 21 (Adoptium Temurin 21 recommended)
- Git

### Build Instructions
Clone the repository:
```bash
git clone https://github.com/meansabine/neo-voxy.git
cd neo-voxy
```

To build the **ARR-safe distributable JAR** (recommended for distribution):
```bash
./gradlew buildDistributableJar
```
*This task automatically downloads the official Fabric Voxy release from Modrinth, compiles Neo-Voxy, generates the binary delta patches, packages the additions, verifies that zero copyrighted classes are bundled, and outputs `build/libs/neovoxy-0.6.1-distributable.jar`.*

To build standard developer artifacts:
```bash
./gradlew build
```

---

## Contributing

This is an unofficial community port.
- If you encounter a bug, first check whether the issue also occurs in the upstream Fabric Voxy mod.
- For NeoForge-specific issues, chunk-streaming problems, or rendering bugs, please open an Issue with complete logs and system specifications.
- Pull Requests are welcome for compatibility fixes, optimizations, and new integrations!

---

## Credits & License

- **Original Voxy Mod & Engine:** Created by [cortex](https://github.com/cortex) ([Modrinth](https://modrinth.com/mod/voxy) | [GitHub](https://github.com/cortex/voxy)).
- **NeoForge Reforged Port & Additions:** Community effort maintained by **meansabine** and contributors.
- **Third-Party Libraries:** RocksDB (Apache 2.0 / BSD), Jedis (MIT), Zstandard-JNI (BSD-2-Clause), XZ for Java (Public Domain), Apache Commons Pool 2 (Apache 2.0).

```
Original Voxy Code:
Copyright 2025 MCRcortex
All rights reserved. Do not redistribute.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```
