# VFX texture packs

All VFX texture packs are stored directly under `src/main/resources/vfx` and are
included in the main resource output and JAR. There is no separate `runtime` or
`source` layer.

Code references the original packaged assets directly:

| Purpose | Packaged path | Source pack | Mask channel | Color space |
| --- | --- | --- | --- | --- |
| fire particle | `particles/kenney/particle_pack/fire_01.png` | Kenney Particle Pack | luminance | linear data |
| smoke particle | `particles/kenney/particle_pack/smoke_03.png` | Kenney Particle Pack | luminance | linear data |
| scorch decal | `decals/kenney/particle_pack/scorch_01.png` | Kenney Particle Pack | luminance | linear data |
| impact ring | `masks/kenney/light_masks/transparent/ring_a_streaks.png` | Kenney Light Masks 1.0 | alpha | linear data |
| directional streak | `masks/kenney/light_masks/transparent/streaks_composed_a_noise.png` | Kenney Light Masks 1.0 | alpha | linear data |

Mask textures are data textures and must not receive sRGB decoding.

## License and provenance

Kenney Particle Pack credits Kenney Vleugels and the filter-template
contributors Indigo Ray, Craig Nisbet, Zoltan Erdokovy, Heliagon, ThreeDee,
Killst4r and Tim2501. Its bundled license declares CC0-1.0.

Kenney Development Essentials 1.1 was created/distributed by Kenney on
2026-01-05. Its bundled license also declares CC0-1.0. The Light Masks content
is the 1.0 pack included in this resource set.

License: https://creativecommons.org/publicdomain/zero/1.0/

Luos and WrizFX author notices, acquisition URLs and redistribution terms are
stored under `src/main/resources/vfx/licenses`. The complete packs are included
in the JAR, so releases must preserve these notices and comply with their terms.

VFX uses package-level provenance in this document and the bundled files under
`vfx/licenses`; individual VFX files are therefore not duplicated in
`docs/licenses/assets.tsv`.
