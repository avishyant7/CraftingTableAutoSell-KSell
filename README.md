# KSell

Standalone Fabric client-side Minecraft mod for 1.21.11 / 26.2.

Commands:
- /ksell on <price>
- /ksel off

Only Respawn Anchors are sold. Each listing is verified to contain exactly one Respawn Anchor. Inventory movement uses Minecraft's real container click API rather than directly mutating inventory stacks.

Build target:
- Java 25
- Gradle 9.5.1
- Fabric Loader 0.19.3
- Fabric API 0.152.1+26.2
