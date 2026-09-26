# Changelog

## [3.10.0](https://github.com/yschimke/compose-preview-daemon/compare/v3.9.0...v3.10.0) (2026-09-26)


### Features

* **build:** gate the published ABI on every JVM and KMP module ([#130](https://github.com/yschimke/compose-preview-daemon/issues/130)) ([b77abcc](https://github.com/yschimke/compose-preview-daemon/commit/b77abcc9be9cd9f3a9288bc471b0a3a1e9fc3f14))
* **build:** publish a BOM describing every daemon coordinate ([#122](https://github.com/yschimke/compose-preview-daemon/issues/122)) ([78bb41e](https://github.com/yschimke/compose-preview-daemon/commit/78bb41eaa3d142ef770e87c6e41270c8bfc5039a))
* **build:** publish only the modules a release changes ([#123](https://github.com/yschimke/compose-preview-daemon/issues/123)) ([63638da](https://github.com/yschimke/compose-preview-daemon/commit/63638da97f75c0a31e439114203d499501b2bf43))
* **client:** DaemonLaunchOptions, the writing half of the property registry ([#35](https://github.com/yschimke/compose-preview-daemon/issues/35)) ([5044447](https://github.com/yschimke/compose-preview-daemon/commit/50444478c52923dff3c2e1486a205d18d0e464b7))
* **client:** DaemonLaunchPlan, so the daemon says how to run itself ([#40](https://github.com/yschimke/compose-preview-daemon/issues/40)) ([cc26b1a](https://github.com/yschimke/compose-preview-daemon/commit/cc26b1ad1c41deb299eef05a2be14e5d6575e696))
* **client:** ManagedDaemon, one daemon's life and nothing more ([#45](https://github.com/yschimke/compose-preview-daemon/issues/45)) ([a8cd6b9](https://github.com/yschimke/compose-preview-daemon/commit/a8cd6b9ade84f5db1b4c1b1822d27be6d4f48f4c))
* **client:** publish DaemonSession, the protocol as an interface ([#33](https://github.com/yschimke/compose-preview-daemon/issues/33)) ([0de1086](https://github.com/yschimke/compose-preview-daemon/commit/0de10860157adbd6d46333836a34afc12ad5f7dd))
* **client:** RobolectricLaunch, the renderer facts without a daemon ([#47](https://github.com/yschimke/compose-preview-daemon/issues/47)) ([d28d31e](https://github.com/yschimke/compose-preview-daemon/commit/d28d31e56fec2ea4da1c3ef411f0138de2254d53))
* **deps:** take contracts 3.0.0 and move to its builders ([#141](https://github.com/yschimke/compose-preview-daemon/issues/141)) ([3232b4f](https://github.com/yschimke/compose-preview-daemon/commit/3232b4fed30f19c2397bcb70d5191d64d150f274))
* **design-pages:** put the wire types behind builders so they can grow ([#139](https://github.com/yschimke/compose-preview-daemon/issues/139)) ([a5cc051](https://github.com/yschimke/compose-preview-daemon/commit/a5cc0517010456a6ecea252a89c1f086dd8a3469))
* **design-pages:** shared background assets and allowlisted blend modes ([#119](https://github.com/yschimke/compose-preview-daemon/issues/119)) ([c577939](https://github.com/yschimke/compose-preview-daemon/commit/c577939ca1870d3fbc5746bf0c4eab5b3cd787f8))
* **fonts:** record the axes a resolution asked for and did not get ([#125](https://github.com/yschimke/compose-preview-daemon/issues/125)) ([71ead85](https://github.com/yschimke/compose-preview-daemon/commit/71ead85c74bf56b54580a3e5dc1b091712020e8e))
* **overrides:** publish the named-override runtime as Kotlin Multiplatform ([#58](https://github.com/yschimke/compose-preview-daemon/issues/58)) ([1eddb3f](https://github.com/yschimke/compose-preview-daemon/commit/1eddb3f024c459bb8ed3d08af515fd94c4daf05c))
* **preview-annotations:** add `related` to @CatalogComponent ([#62](https://github.com/yschimke/compose-preview-daemon/issues/62)) ([88bc484](https://github.com/yschimke/compose-preview-daemon/commit/88bc4849d525bbac53188328011118cd2094afc5))
* stand the repository up around the imported renderers, extractors and daemon ([a80e6a5](https://github.com/yschimke/compose-preview-daemon/commit/a80e6a51f508c4e3ed21c8fa112b8574c0b380e6))
* stand the repository up around the imported renderers, extractors and daemon ([bbdef71](https://github.com/yschimke/compose-preview-daemon/commit/bbdef71f928c9889cb8591358ce1bce53cd68c77))
* start daemon processes with a minimal environment ([#161](https://github.com/yschimke/compose-preview-daemon/issues/161)) ([13cfae7](https://github.com/yschimke/compose-preview-daemon/commit/13cfae77b8b668283f5ab6dcf61ee8aef9b60d53))


### Bug Fixes

* **build:** enforce daemon dependency ownership in CI ([#42](https://github.com/yschimke/compose-preview-daemon/issues/42)) ([37fb992](https://github.com/yschimke/compose-preview-daemon/commit/37fb99236430cc0732fc1caeb376bbdf7d70f261))
* **build:** gate the six published JVM modules a bad classifier missed ([#140](https://github.com/yschimke/compose-preview-daemon/issues/140)) ([7fc3e4a](https://github.com/yschimke/compose-preview-daemon/commit/7fc3e4a92b497a1433edac2369655e7bb6167632))
* **build:** read the publish baseline from Maven Central, not from git ([#144](https://github.com/yschimke/compose-preview-daemon/issues/144)) ([20925fa](https://github.com/yschimke/compose-preview-daemon/commit/20925facad3af20f03ec54e070d85528e8682bf8))
* **build:** refresh dependency locks after version bumps ([#43](https://github.com/yschimke/compose-preview-daemon/issues/43)) ([3fb26b9](https://github.com/yschimke/compose-preview-daemon/commit/3fb26b931763fd3ec875ed18c42a3d9252e32d59))
* **build:** remove inherited dependency locking ([#44](https://github.com/yschimke/compose-preview-daemon/issues/44)) ([765cf69](https://github.com/yschimke/compose-preview-daemon/commit/765cf69656cea87953bb4ed9ae13238d25a107e1))
* **build:** stop lint checking a device API floor against host-JVM code ([#136](https://github.com/yschimke/compose-preview-daemon/issues/136)) ([9b5808d](https://github.com/yschimke/compose-preview-daemon/commit/9b5808d3bb5d1e75e5e5d8344670f6a87a0a19a4))
* **ci:** grant the release caller the pull-request permission it now needs ([#131](https://github.com/yschimke/compose-preview-daemon/issues/131)) ([d627aa4](https://github.com/yschimke/compose-preview-daemon/commit/d627aa489990dc99f86d9b21fec53df122469654))
* **ci:** never let recording a publish fail the release ([#127](https://github.com/yschimke/compose-preview-daemon/issues/127)) ([e7d85cc](https://github.com/yschimke/compose-preview-daemon/commit/e7d85cccf8d3648c64b5f8bc955e5c5eb422320b))
* **ci:** refuse to release when no publish task resolves ([#145](https://github.com/yschimke/compose-preview-daemon/issues/145)) ([2628878](https://github.com/yschimke/compose-preview-daemon/commit/26288780902ff0608244a666c728942cce90375b))
* correct activity chrome and root-relative bounds ([#93](https://github.com/yschimke/compose-preview-daemon/issues/93)) ([59a1ec2](https://github.com/yschimke/compose-preview-daemon/commit/59a1ec2e3fb3031e1e8518b7965c420e706e3e87))
* **daemon:** classify a Skiko bindings/native mismatch as classpath skew ([#158](https://github.com/yschimke/compose-preview-daemon/issues/158)) ([af03b87](https://github.com/yschimke/compose-preview-daemon/commit/af03b877e1f1d701766cc81d6e681c8482960c05))
* **daemon:** delegate the choice-knob option type to the parent loader ([#99](https://github.com/yschimke/compose-preview-daemon/issues/99)) ([fe36def](https://github.com/yschimke/compose-preview-daemon/commit/fe36def3240e1485371509b12485bea51405d7df))
* **deps:** consume the contracts fix for flattened painter fills ([#152](https://github.com/yschimke/compose-preview-daemon/issues/152)) ([a9d2b99](https://github.com/yschimke/compose-preview-daemon/commit/a9d2b995f9c4bbb8d6d126ee950e1611e6c6ea4c))
* **deps:** update androidx ([#16](https://github.com/yschimke/compose-preview-daemon/issues/16)) ([1b6111f](https://github.com/yschimke/compose-preview-daemon/commit/1b6111f421850d93984592b849ba4c19c249b330))
* **deps:** update androidx-compose ([#17](https://github.com/yschimke/compose-preview-daemon/issues/17)) ([a40b9a7](https://github.com/yschimke/compose-preview-daemon/commit/a40b9a714a029f19399eaddb8bcd604c06a583a8))
* **deps:** update androidx-wear to v1.7.0-rc01 ([#18](https://github.com/yschimke/compose-preview-daemon/issues/18)) ([157b1e2](https://github.com/yschimke/compose-preview-daemon/commit/157b1e2548686b4b11cd322c704230b7a49830ca))
* **deps:** update compose-ai-tools to v2.14.0 ([#21](https://github.com/yschimke/compose-preview-daemon/issues/21)) ([a05c130](https://github.com/yschimke/compose-preview-daemon/commit/a05c130e72c7429d588a3d32f2835fe302582eb9))
* **deps:** update compose-ai-tools to v2.15.0 ([#37](https://github.com/yschimke/compose-preview-daemon/issues/37)) ([7c4cb11](https://github.com/yschimke/compose-preview-daemon/commit/7c4cb118b424879306518d8c558c86d6036c1a68))
* **deps:** update compose-ai-tools to v2.16.0 ([#53](https://github.com/yschimke/compose-preview-daemon/issues/53)) ([a7272e3](https://github.com/yschimke/compose-preview-daemon/commit/a7272e399d89df3cea0922fb97b1035fd78fd11d))
* **deps:** update compose-ai-tools to v2.17.0 ([#91](https://github.com/yschimke/compose-preview-daemon/issues/91)) ([1cb3ee9](https://github.com/yschimke/compose-preview-daemon/commit/1cb3ee9a288e3c80599ce31df0eeda919f678d59))
* **deps:** update compose-preview-contracts to v2.14.0 ([#22](https://github.com/yschimke/compose-preview-daemon/issues/22)) ([149dc57](https://github.com/yschimke/compose-preview-daemon/commit/149dc573eb4c546a5722e2d5ea0882432aac21eb))
* **deps:** update compose-preview-contracts to v2.15.0 ([#38](https://github.com/yschimke/compose-preview-daemon/issues/38)) ([2e27b3b](https://github.com/yschimke/compose-preview-daemon/commit/2e27b3b1d2b4cb4483e69b22f595a104c6e39fe8))
* **deps:** update compose-preview-contracts to v2.16.0 ([#54](https://github.com/yschimke/compose-preview-daemon/issues/54)) ([a639d13](https://github.com/yschimke/compose-preview-daemon/commit/a639d139db09b0455a952eb4bcf4c015a2cd77d2))
* **deps:** update compose-preview-contracts to v2.17.0 ([#92](https://github.com/yschimke/compose-preview-daemon/issues/92)) ([0527044](https://github.com/yschimke/compose-preview-daemon/commit/05270445630032b678bd199eba7969acad4851f2))
* **deps:** update compose-preview-contracts to v2.18.0 ([#96](https://github.com/yschimke/compose-preview-daemon/issues/96)) ([99c35fa](https://github.com/yschimke/compose-preview-daemon/commit/99c35fa0df2937d4e89452f2eb3b37fa8ba3bcf5))
* **deps:** update compose-preview-contracts to v2.19.0 ([#118](https://github.com/yschimke/compose-preview-daemon/issues/118)) ([58fc973](https://github.com/yschimke/compose-preview-daemon/commit/58fc973206e95c4d4525013780378e646b0f1a67))
* **deps:** update dependency ee.schimke.composeai:compose-preview-serve to v3.22.0 ([#23](https://github.com/yschimke/compose-preview-daemon/issues/23)) ([0058038](https://github.com/yschimke/compose-preview-daemon/commit/0058038c33bca8ad7cdc47bf6c5a1a20104215cf))
* **deps:** update dependency ee.schimke.composeai:compose-preview-serve to v3.23.0 ([#39](https://github.com/yschimke/compose-preview-daemon/issues/39)) ([8bebf11](https://github.com/yschimke/compose-preview-daemon/commit/8bebf11d43d930b844b52c6d7310805c0e65b099))
* **deps:** update dependency ee.schimke.composeai:third-party-rc-embedded-player to v1.61.1 ([#97](https://github.com/yschimke/compose-preview-daemon/issues/97)) ([e02d014](https://github.com/yschimke/compose-preview-daemon/commit/e02d0145c464eb82a774cd8b7ae70f5be613fcf0))
* **deps:** update dependency ee.schimke.composeai:third-party-rc-embedded-player to v1.63.0 ([#120](https://github.com/yschimke/compose-preview-daemon/issues/120)) ([e7e1b73](https://github.com/yschimke/compose-preview-daemon/commit/e7e1b730ef722253f5527ea9366122245a688a36))
* **deps:** update dependency org.robolectric:robolectric to v4.17 ([#51](https://github.com/yschimke/compose-preview-daemon/issues/51)) ([d7da242](https://github.com/yschimke/compose-preview-daemon/commit/d7da24219eda9ee81c9d363ee294dda309b7f4db))
* **deps:** update kotlin to v2.4.20 ([#19](https://github.com/yschimke/compose-preview-daemon/issues/19)) ([0b7645d](https://github.com/yschimke/compose-preview-daemon/commit/0b7645d66f026fd8192a503f072853612ffa13e7))
* **deps:** update metro to v1.4.3 ([#20](https://github.com/yschimke/compose-preview-daemon/issues/20)) ([f386066](https://github.com/yschimke/compose-preview-daemon/commit/f3860668e5e900d710d489d648bb6aa2a0bfca36))
* **deps:** update rc-players to v1.60.1 ([#24](https://github.com/yschimke/compose-preview-daemon/issues/24)) ([d814536](https://github.com/yschimke/compose-preview-daemon/commit/d814536e460a54c89bfde51b878248a74658c4e9))
* **deps:** update rc-players to v1.60.2 ([#36](https://github.com/yschimke/compose-preview-daemon/issues/36)) ([fc59972](https://github.com/yschimke/compose-preview-daemon/commit/fc59972baf3db4df5ea2967cafad073024c78843))
* **design-pages:** keep an unrecognised enum a parse failure ([#128](https://github.com/yschimke/compose-preview-daemon/issues/128)) ([61a4d34](https://github.com/yschimke/compose-preview-daemon/commit/61a4d34906b60d350b0c1ab403ad004b27d10afd))
* **figma-svg:** don't degrade an export that draws no text ([#11](https://github.com/yschimke/compose-preview-daemon/issues/11)) ([0104e4f](https://github.com/yschimke/compose-preview-daemon/commit/0104e4f6eadae6d98a72a865c02764c7e9ad56db))
* **figma-svg:** draw Glimmer surfaces and brush painters as SVG primitives ([#148](https://github.com/yschimke/compose-preview-daemon/issues/148)) ([b0ff851](https://github.com/yschimke/compose-preview-daemon/commit/b0ff8516479d59c4f3be428543581db89adc4fc8))
* keep discovery drains within their save boundary ([#65](https://github.com/yschimke/compose-preview-daemon/issues/65)) ([e9fb7bf](https://github.com/yschimke/compose-preview-daemon/commit/e9fb7bf0c2c0c554138675803a29051044a78fdd))
* keep Glimmer live previews initially unfocused ([#109](https://github.com/yschimke/compose-preview-daemon/issues/109)) ([70f207f](https://github.com/yschimke/compose-preview-daemon/commit/70f207f3a608d579861512238e5cac58d61553fb))
* model Glimmer live gaze without shrinking defaults ([#111](https://github.com/yschimke/compose-preview-daemon/issues/111)) ([c0bc949](https://github.com/yschimke/compose-preview-daemon/commit/c0bc949961fd57ef4b17d72e1c0ff3ae66d16dc0))
* **release:** skip empty Maven publications ([#155](https://github.com/yschimke/compose-preview-daemon/issues/155)) ([d55d1ca](https://github.com/yschimke/compose-preview-daemon/commit/d55d1caaca17c6cedd3e451493429811faf8088e))
* **renderer:** emit catalog sidecars from SideEffect, not remember ([#137](https://github.com/yschimke/compose-preview-daemon/issues/137)) ([b944ee0](https://github.com/yschimke/compose-preview-daemon/commit/b944ee0958ec058cea0e5d8d6a557e1b80e13609))
* **renderer:** hide host action bar before composition ([#74](https://github.com/yschimke/compose-preview-daemon/issues/74)) ([b12bbec](https://github.com/yschimke/compose-preview-daemon/commit/b12bbecc28187fa8f780c127ad48c5691124fd57))
* stabilize Glimmer previews and Wear ambient rendering ([#107](https://github.com/yschimke/compose-preview-daemon/issues/107)) ([9b7fdf4](https://github.com/yschimke/compose-preview-daemon/commit/9b7fdf43864fe9eda3da68866ddb4010801556f7))


### Performance

* bound compiler work in Android daemon launch plans ([#79](https://github.com/yschimke/compose-preview-daemon/issues/79)) ([d48dee4](https://github.com/yschimke/compose-preview-daemon/commit/d48dee41a0deec9bf5be7287a114f71bd8a0e837))
* bulk copy canonical ARGB settling snapshots ([#80](https://github.com/yschimke/compose-preview-daemon/issues/80)) ([0ded8ce](https://github.com/yschimke/compose-preview-daemon/commit/0ded8ce684705fc3d4b8b9560df4ee9b8df30f53))
* **daemon:** adopt pre-booted spare sandbox workers instead of booting ([#8](https://github.com/yschimke/compose-preview-daemon/issues/8)) ([94fc815](https://github.com/yschimke/compose-preview-daemon/commit/94fc815855b0dddb87c166b595a5af57550a70b0))
* **daemon:** benchmark startup tuning, bindings and CDS ([#49](https://github.com/yschimke/compose-preview-daemon/issues/49)) ([9a7d7b7](https://github.com/yschimke/compose-preview-daemon/commit/9a7d7b7cc5421c0963b2a8a9a64a009ddcc53d0f))
* **daemon:** defer the in-process sandbox behind adopted spares, hand them back on shutdown, and prove it end to end ([#9](https://github.com/yschimke/compose-preview-daemon/issues/9)) ([d109aa7](https://github.com/yschimke/compose-preview-daemon/commit/d109aa775fb2865264c9e47001c8409f022117b8))
* **daemon:** hand the encoded frame back from the desktop render ([#25](https://github.com/yschimke/compose-preview-daemon/issues/25)) ([4bbb34e](https://github.com/yschimke/compose-preview-daemon/commit/4bbb34e668bbf3ba4478c5ba4ac469861aa27999))
* **daemon:** measure startup overlap and loaded worker efficiency ([#56](https://github.com/yschimke/compose-preview-daemon/issues/56)) ([18b8237](https://github.com/yschimke/compose-preview-daemon/commit/18b82373ac1024eb603c95646c4927e2d6a27b95))
* **daemon:** profile startup and benchmark compiler tuning ([#34](https://github.com/yschimke/compose-preview-daemon/issues/34)) ([08d095e](https://github.com/yschimke/compose-preview-daemon/commit/08d095e4c842b708b159ce32cbc8e87652417305))
* **daemon:** prototype and measure activity-free capture ([#41](https://github.com/yschimke/compose-preview-daemon/issues/41)) ([5f9199f](https://github.com/yschimke/compose-preview-daemon/commit/5f9199fcfb917e421c20a171496744eb8b8c8306))
* reduce capture CPU and release reloaded classes ([#60](https://github.com/yschimke/compose-preview-daemon/issues/60)) ([0d00f1d](https://github.com/yschimke/compose-preview-daemon/commit/0d00f1d56116a8aafbac71bb311dcd62b37e9f8d))
* reduce inspector CPU during repeated captures ([#66](https://github.com/yschimke/compose-preview-daemon/issues/66)) ([44617e3](https://github.com/yschimke/compose-preview-daemon/commit/44617e34d12d421be2c82769dcacd84ca95ded8f))
* reuse decoded still images for size correction ([#69](https://github.com/yschimke/compose-preview-daemon/issues/69)) ([027d678](https://github.com/yschimke/compose-preview-daemon/commit/027d6788e558c64e0af7b4d46356c558dba0a9de))
* reuse owned pixel buffers during visual settling ([#88](https://github.com/yschimke/compose-preview-daemon/issues/88)) ([5d3a2e3](https://github.com/yschimke/compose-preview-daemon/commit/5d3a2e309dc930b6ca00a921816ec86ad0355da4))
* reuse the final settling pixel snapshot ([#76](https://github.com/yschimke/compose-preview-daemon/issues/76)) ([d8c4bbb](https://github.com/yschimke/compose-preview-daemon/commit/d8c4bbba35a45b4d91bc7275b1acada58a10fc63))
* settle still frames before PNG encoding ([#72](https://github.com/yschimke/compose-preview-daemon/issues/72)) ([271a8e7](https://github.com/yschimke/compose-preview-daemon/commit/271a8e7256159f0973a5e00d03377fbbc878f5e5))
* support sampled post-GC render metrics ([#82](https://github.com/yschimke/compose-preview-daemon/issues/82)) ([f8ee665](https://github.com/yschimke/compose-preview-daemon/commit/f8ee665b6739e3bcbc66609ae4018d16c522abe9))


### Refactoring

* merge overrides onto the RenderSpec, not a separate DTO ([#28](https://github.com/yschimke/compose-preview-daemon/issues/28)) ([1892eda](https://github.com/yschimke/compose-preview-daemon/commit/1892eda74e463dbfa0eec8c8b59dd0786e8cf84f))
* one shared override merge instead of six per-lane copies ([#10](https://github.com/yschimke/compose-preview-daemon/issues/10)) ([1494548](https://github.com/yschimke/compose-preview-daemon/commit/1494548440851a5f59a77e0fa1918976ad79d9cd))
* one typed RenderSpec and RenderTarget instead of a payload string ([#7](https://github.com/yschimke/compose-preview-daemon/issues/7)) ([290feb8](https://github.com/yschimke/compose-preview-daemon/commit/290feb8e993ff157e8236327531f4940ef73c44d))
* RenderResult carries a RenderArtifact instead of a pngPath string ([#13](https://github.com/yschimke/compose-preview-daemon/issues/13)) ([aae75ed](https://github.com/yschimke/compose-preview-daemon/commit/aae75ed034fdd077038f00ca433020af707b691e))


### Documentation

* attribute reload memory and measure initial heap sizing ([#73](https://github.com/yschimke/compose-preview-daemon/issues/73)) ([26e6722](https://github.com/yschimke/compose-preview-daemon/commit/26e672264abdd9a7b8d2e4cc363a3bc39895f04b))
* **compat:** test the next Compose line at runtime, the way a consumer does ([#150](https://github.com/yschimke/compose-preview-daemon/issues/150)) ([a0565dc](https://github.com/yschimke/compose-preview-daemon/commit/a0565dc83ea95a353163c65bf0b90b3bd252620f))
* **daemon:** handoff for the boot roadmap's Tier B (Robolectric fork) ([#27](https://github.com/yschimke/compose-preview-daemon/issues/27)) ([444fd87](https://github.com/yschimke/compose-preview-daemon/commit/444fd874576301eb02c9b1d1732644a078d4d41f))
* measure C1 compilation across long reload sessions ([#89](https://github.com/yschimke/compose-preview-daemon/issues/89)) ([7897bc3](https://github.com/yschimke/compose-preview-daemon/commit/7897bc38bdb7913f4cbe7be81dc1e7dd68e09dfc))
* measure explicit GC under bounded Serial heaps ([#81](https://github.com/yschimke/compose-preview-daemon/issues/81)) ([7c98850](https://github.com/yschimke/compose-preview-daemon/commit/7c98850d6a3658cfe699880c9114a0dafe086c1b))
* measure periodic native trimming with sampled metrics ([#85](https://github.com/yschimke/compose-preview-daemon/issues/85)) ([e9ef8a0](https://github.com/yschimke/compose-preview-daemon/commit/e9ef8a0e3fe9906df32525963ac58c912104c496))
* prioritize Robolectric upstream feedback ([#64](https://github.com/yschimke/compose-preview-daemon/issues/64)) ([28e0f43](https://github.com/yschimke/compose-preview-daemon/commit/28e0f43d69581a34f0af140ff5ff66560269d9bf))
* propose an embedding API for the daemon ([#31](https://github.com/yschimke/compose-preview-daemon/issues/31)) ([c7ff645](https://github.com/yschimke/compose-preview-daemon/commit/c7ff645b721d4e553a88802621ccf05f9565a56c))
* qualify worker memory policies under reloads ([#86](https://github.com/yschimke/compose-preview-daemon/issues/86)) ([ffb02d4](https://github.com/yschimke/compose-preview-daemon/commit/ffb02d431d7b3b1b0e1b06599af206cce3e67f22))
* recheck field caching under current worker profiles ([#87](https://github.com/yschimke/compose-preview-daemon/issues/87)) ([081eac9](https://github.com/yschimke/compose-preview-daemon/commit/081eac9946162bf05fda4c7b6d07dc1097edec47))
* record reload soak and explicit GC tradeoffs ([#61](https://github.com/yschimke/compose-preview-daemon/issues/61)) ([255730b](https://github.com/yschimke/compose-preview-daemon/commit/255730b403f545ab8e7ec147246eddc088d469b8))
* research use cases for the render daemon ([#101](https://github.com/yschimke/compose-preview-daemon/issues/101)) ([df96a1c](https://github.com/yschimke/compose-preview-daemon/commit/df96a1c43e26e76dea2595b19988f264a93a6595))
* validate compiler limits across reloads and concurrent workers ([#78](https://github.com/yschimke/compose-preview-daemon/issues/78)) ([5d14855](https://github.com/yschimke/compose-preview-daemon/commit/5d14855b6f51c29b348cbeb7e1ff6507607579e7))
* validate sampled metrics with concurrent workers ([#83](https://github.com/yschimke/compose-preview-daemon/issues/83)) ([d0db011](https://github.com/yschimke/compose-preview-daemon/commit/d0db011c4dea964281242af568ee9c59103a6409))


### Build

* make automated Gradle lock portable ([#106](https://github.com/yschimke/compose-preview-daemon/issues/106)) ([19730c4](https://github.com/yschimke/compose-preview-daemon/commit/19730c4bb42141dbdaeb711b96850ef6bf28e6cd))
* throttle automated Gradle runs ([#105](https://github.com/yschimke/compose-preview-daemon/issues/105)) ([976cc85](https://github.com/yschimke/compose-preview-daemon/commit/976cc859536b29211c2062b7316774e5cee50cf2))


### CI

* create the git tag for the draft release before publishing from it ([#5](https://github.com/yschimke/compose-preview-daemon/issues/5)) ([9faed3a](https://github.com/yschimke/compose-preview-daemon/commit/9faed3a75260485743b52771db4fb3ec1b488de3))
* drop desktopTest from the module test fan-out ([41917ba](https://github.com/yschimke/compose-preview-daemon/commit/41917bab711102dff3d38c7f7c26fc050711796e))
* fail when release-please cannot parse a commit message ([#116](https://github.com/yschimke/compose-preview-daemon/issues/116)) ([e084b6e](https://github.com/yschimke/compose-preview-daemon/commit/e084b6e765f321b45b02ad251cf5b97c00b702a6))
* retarget the Renovate config at this repository ([#15](https://github.com/yschimke/compose-preview-daemon/issues/15)) ([6fa111e](https://github.com/yschimke/compose-preview-daemon/commit/6fa111ebe907a49c032d71abb668fc171d74acd8))
* run Android lint on every pull request ([#138](https://github.com/yschimke/compose-preview-daemon/issues/138)) ([7cb1cc7](https://github.com/yschimke/compose-preview-daemon/commit/7cb1cc71ecf55de398266aa12461967556b3b865))


### Chores

* **deps:** pin dependencies ([#156](https://github.com/yschimke/compose-preview-daemon/issues/156)) ([05b55f7](https://github.com/yschimke/compose-preview-daemon/commit/05b55f7786dbb7c29bedd5f63ea52b10d12b4537))
* **deps:** update actions/setup-java action to v6 ([#55](https://github.com/yschimke/compose-preview-daemon/issues/55)) ([e0b94e5](https://github.com/yschimke/compose-preview-daemon/commit/e0b94e53e78ccb1a144733ca1e986cc8acad7902))
* **deps:** update actions/setup-java action to v6.0.1 ([#57](https://github.com/yschimke/compose-preview-daemon/issues/57)) ([a1c7963](https://github.com/yschimke/compose-preview-daemon/commit/a1c79630dec525ddf78a5d63dd8f71177ab83ae8))
* **deps:** update github-actions ([#52](https://github.com/yschimke/compose-preview-daemon/issues/52)) ([e513293](https://github.com/yschimke/compose-preview-daemon/commit/e513293075f6aea5fcf991f31b4aaf13f379cdd5))
* **deps:** update github-actions to v7 ([#157](https://github.com/yschimke/compose-preview-daemon/issues/157)) ([df29e25](https://github.com/yschimke/compose-preview-daemon/commit/df29e254d38cc1786ff79440b6defe06e20f2f0a))
* **deps:** update gradle to v9.7.1 ([#50](https://github.com/yschimke/compose-preview-daemon/issues/50)) ([29fc3cf](https://github.com/yschimke/compose-preview-daemon/commit/29fc3cfe8233cd9a1f1b8bde1c6cf2f67beec28e))
* drop the catalog entries the compose-ai-tools split left behind ([#95](https://github.com/yschimke/compose-preview-daemon/issues/95)) ([325b632](https://github.com/yschimke/compose-preview-daemon/commit/325b632ee0615ced94d4a9379e0b926689bf1ff8))
* **main:** release 3.0.0 ([#3](https://github.com/yschimke/compose-preview-daemon/issues/3)) ([2b0a7aa](https://github.com/yschimke/compose-preview-daemon/commit/2b0a7aac6e26d17e210d1a462546748b18bdc029))
* **main:** release 3.0.1 ([#6](https://github.com/yschimke/compose-preview-daemon/issues/6)) ([53a7dc9](https://github.com/yschimke/compose-preview-daemon/commit/53a7dc9e9f12d15f15b03465883d1df69519b65d))
* **main:** release 3.0.2 ([#12](https://github.com/yschimke/compose-preview-daemon/issues/12)) ([4348420](https://github.com/yschimke/compose-preview-daemon/commit/434842082008e6007f7079d253320f52f4376775))
* **main:** release 3.0.3 ([#29](https://github.com/yschimke/compose-preview-daemon/issues/29)) ([fd83042](https://github.com/yschimke/compose-preview-daemon/commit/fd83042a66753303004049164f3b519d28411422))
* **main:** release 3.1.0 ([#30](https://github.com/yschimke/compose-preview-daemon/issues/30)) ([91a10b6](https://github.com/yschimke/compose-preview-daemon/commit/91a10b67489fb3db10af443cc955047ccc427a33))
* **main:** release 3.2.0 ([#46](https://github.com/yschimke/compose-preview-daemon/issues/46)) ([a64b441](https://github.com/yschimke/compose-preview-daemon/commit/a64b44192492a400b277c8359da61f53c3042067))
* **main:** release 3.3.0 ([#48](https://github.com/yschimke/compose-preview-daemon/issues/48)) ([a1d8520](https://github.com/yschimke/compose-preview-daemon/commit/a1d85201d426a8799abfae14993087a9189f055e))
* **main:** release 3.4.0 ([#59](https://github.com/yschimke/compose-preview-daemon/issues/59)) ([fe87b9a](https://github.com/yschimke/compose-preview-daemon/commit/fe87b9a61fa9b15702dcd2b024b4485bceffca85))
* **main:** release 3.4.1 ([#71](https://github.com/yschimke/compose-preview-daemon/issues/71)) ([bea621b](https://github.com/yschimke/compose-preview-daemon/commit/bea621b01b0a58da02edb2b37299141e43a37ef0))
* **main:** release 3.4.2 ([#75](https://github.com/yschimke/compose-preview-daemon/issues/75)) ([2b35c36](https://github.com/yschimke/compose-preview-daemon/commit/2b35c36b8620496e482797eefb246c4b01c01ad1))
* **main:** release 3.4.3 ([#90](https://github.com/yschimke/compose-preview-daemon/issues/90)) ([5070eed](https://github.com/yschimke/compose-preview-daemon/commit/5070eed5aa5cd40bd8ed9490e501aeafbd545eeb))
* **main:** release 3.4.4 ([#94](https://github.com/yschimke/compose-preview-daemon/issues/94)) ([480219f](https://github.com/yschimke/compose-preview-daemon/commit/480219f0ca7cab76038ddba5f07f305dfae26b4a))
* **main:** release 3.4.5 ([#98](https://github.com/yschimke/compose-preview-daemon/issues/98)) ([b5fde5e](https://github.com/yschimke/compose-preview-daemon/commit/b5fde5e6b32fff4ec4d812e318bf2a91f45be447))
* **main:** release 3.4.6 ([#100](https://github.com/yschimke/compose-preview-daemon/issues/100)) ([c5fc415](https://github.com/yschimke/compose-preview-daemon/commit/c5fc415b494776fa7f96813c889acebacba6ad28))
* **main:** release 3.4.7 ([#108](https://github.com/yschimke/compose-preview-daemon/issues/108)) ([96a2981](https://github.com/yschimke/compose-preview-daemon/commit/96a298152b3c9bed2390d25a5770d74d25af25b0))
* **main:** release 3.4.8 ([#110](https://github.com/yschimke/compose-preview-daemon/issues/110)) ([975a2e5](https://github.com/yschimke/compose-preview-daemon/commit/975a2e53c07fa4f06b89f293df0b3b1007e8745e))
* **main:** release 3.4.9 ([#112](https://github.com/yschimke/compose-preview-daemon/issues/112)) ([aa6bc52](https://github.com/yschimke/compose-preview-daemon/commit/aa6bc521aab8311a36ddf68acca01dcc05a0a5f3))
* **main:** release 3.5.0 ([#117](https://github.com/yschimke/compose-preview-daemon/issues/117)) ([0959f50](https://github.com/yschimke/compose-preview-daemon/commit/0959f50b2fa783c86c91099ef8d8cd83431cb249))
* **main:** release 3.6.0 ([#121](https://github.com/yschimke/compose-preview-daemon/issues/121)) ([16e847f](https://github.com/yschimke/compose-preview-daemon/commit/16e847ffe1ae0808d5effc157041a67cfee92f69))
* **main:** release 3.6.1 ([#126](https://github.com/yschimke/compose-preview-daemon/issues/126)) ([e8554a0](https://github.com/yschimke/compose-preview-daemon/commit/e8554a0cfa1b198e7785e63590717e939a227a5c))
* **main:** release 3.7.0 ([#132](https://github.com/yschimke/compose-preview-daemon/issues/132)) ([10bbac8](https://github.com/yschimke/compose-preview-daemon/commit/10bbac8272d5c6d86da6fc56eb7d61e5bf6aeb0b))
* **main:** release 3.8.0 ([#134](https://github.com/yschimke/compose-preview-daemon/issues/134)) ([2e1fc94](https://github.com/yschimke/compose-preview-daemon/commit/2e1fc94e76ea823fa6f0421b42568e03562989b0))
* **main:** release 3.8.1 ([#146](https://github.com/yschimke/compose-preview-daemon/issues/146)) ([98b2a96](https://github.com/yschimke/compose-preview-daemon/commit/98b2a9698e00273d75c940a3662aef453deedaec))
* **main:** release 3.8.2 ([#147](https://github.com/yschimke/compose-preview-daemon/issues/147)) ([0f8288b](https://github.com/yschimke/compose-preview-daemon/commit/0f8288b8ad972c7885d5c72554b8dd148f06dcf2))
* **main:** release 3.8.3 ([#151](https://github.com/yschimke/compose-preview-daemon/issues/151)) ([228e183](https://github.com/yschimke/compose-preview-daemon/commit/228e1836f004330940f43bcde4647ef48285a9fc))
* **main:** release 3.8.4 ([#153](https://github.com/yschimke/compose-preview-daemon/issues/153)) ([f00fdcb](https://github.com/yschimke/compose-preview-daemon/commit/f00fdcb3bed7d811b17109ded7b24e83ff19b2fe))
* **main:** release 3.9.0 ([#159](https://github.com/yschimke/compose-preview-daemon/issues/159)) ([6b9e97f](https://github.com/yschimke/compose-preview-daemon/commit/6b9e97ff64d1fa5d02c9f243cffe3ffe216c4fce))
* record published versions for v3.6.1 ([#133](https://github.com/yschimke/compose-preview-daemon/issues/133)) ([fd363fb](https://github.com/yschimke/compose-preview-daemon/commit/fd363fbb2840ea1e1612a4fb646506ad77a068a5))
* record published versions for v3.7.0 ([#135](https://github.com/yschimke/compose-preview-daemon/issues/135)) ([75f9c9a](https://github.com/yschimke/compose-preview-daemon/commit/75f9c9a45b159e086908999ee7529e98cd04b2fd))
* record published versions for v3.8.0 ([#143](https://github.com/yschimke/compose-preview-daemon/issues/143)) ([ec6a151](https://github.com/yschimke/compose-preview-daemon/commit/ec6a15150268691bb8df05e4fba70f32afe98231))
* release the first version from this repository as 3.0.0 ([cbfc494](https://github.com/yschimke/compose-preview-daemon/commit/cbfc494760da9370537726c7b9f973427080f589))
* release the first version from this repository as 3.0.0 ([50cab15](https://github.com/yschimke/compose-preview-daemon/commit/50cab15e3c3c3719561bf752d964033faf1cf7d6))
* start the changelog at the import, not at the imported history ([3c74631](https://github.com/yschimke/compose-preview-daemon/commit/3c746318f9a84d56bab1c69c38cfab8b6d09cb90))

## [3.9.0](https://github.com/yschimke/compose-preview-daemon/compare/v3.8.4...v3.9.0) (2026-09-26)


### Features

* start daemon processes with a minimal environment ([#161](https://github.com/yschimke/compose-preview-daemon/issues/161)) ([13cfae7](https://github.com/yschimke/compose-preview-daemon/commit/13cfae77b8b668283f5ab6dcf61ee8aef9b60d53))

## [3.8.4](https://github.com/yschimke/compose-preview-daemon/compare/v3.8.3...v3.8.4) (2026-09-25)


### Bug Fixes

* **daemon:** classify a Skiko bindings/native mismatch as classpath skew ([#158](https://github.com/yschimke/compose-preview-daemon/issues/158)) ([af03b87](https://github.com/yschimke/compose-preview-daemon/commit/af03b877e1f1d701766cc81d6e681c8482960c05))
* **release:** skip empty Maven publications ([#155](https://github.com/yschimke/compose-preview-daemon/issues/155)) ([d55d1ca](https://github.com/yschimke/compose-preview-daemon/commit/d55d1caaca17c6cedd3e451493429811faf8088e))


### Chores

* **deps:** pin dependencies ([#156](https://github.com/yschimke/compose-preview-daemon/issues/156)) ([05b55f7](https://github.com/yschimke/compose-preview-daemon/commit/05b55f7786dbb7c29bedd5f63ea52b10d12b4537))
* **deps:** update github-actions to v7 ([#157](https://github.com/yschimke/compose-preview-daemon/issues/157)) ([df29e25](https://github.com/yschimke/compose-preview-daemon/commit/df29e254d38cc1786ff79440b6defe06e20f2f0a))

## [3.8.3](https://github.com/yschimke/compose-preview-daemon/compare/v3.8.2...v3.8.3) (2026-09-17)


### Bug Fixes

* **deps:** consume the contracts fix for flattened painter fills ([#152](https://github.com/yschimke/compose-preview-daemon/issues/152)) ([a9d2b99](https://github.com/yschimke/compose-preview-daemon/commit/a9d2b995f9c4bbb8d6d126ee950e1611e6c6ea4c))

## [3.8.2](https://github.com/yschimke/compose-preview-daemon/compare/v3.8.1...v3.8.2) (2026-09-17)


### Bug Fixes

* **figma-svg:** draw Glimmer surfaces and brush painters as SVG primitives ([#148](https://github.com/yschimke/compose-preview-daemon/issues/148)) ([b0ff851](https://github.com/yschimke/compose-preview-daemon/commit/b0ff8516479d59c4f3be428543581db89adc4fc8))


### Documentation

* **compat:** test the next Compose line at runtime, the way a consumer does ([#150](https://github.com/yschimke/compose-preview-daemon/issues/150)) ([a0565dc](https://github.com/yschimke/compose-preview-daemon/commit/a0565dc83ea95a353163c65bf0b90b3bd252620f))

## [3.8.1](https://github.com/yschimke/compose-preview-daemon/compare/v3.8.0...v3.8.1) (2026-09-17)


### Bug Fixes

* **build:** read the publish baseline from Maven Central, not from git ([#144](https://github.com/yschimke/compose-preview-daemon/issues/144)) ([20925fa](https://github.com/yschimke/compose-preview-daemon/commit/20925facad3af20f03ec54e070d85528e8682bf8))
* **ci:** refuse to release when no publish task resolves ([#145](https://github.com/yschimke/compose-preview-daemon/issues/145)) ([2628878](https://github.com/yschimke/compose-preview-daemon/commit/26288780902ff0608244a666c728942cce90375b))


### Chores

* record published versions for v3.8.0 ([#143](https://github.com/yschimke/compose-preview-daemon/issues/143)) ([ec6a151](https://github.com/yschimke/compose-preview-daemon/commit/ec6a15150268691bb8df05e4fba70f32afe98231))

## [3.8.0](https://github.com/yschimke/compose-preview-daemon/compare/v3.7.0...v3.8.0) (2026-09-16)


### Features

* **deps:** take contracts 3.0.0 and move to its builders ([#141](https://github.com/yschimke/compose-preview-daemon/issues/141)) ([3232b4f](https://github.com/yschimke/compose-preview-daemon/commit/3232b4fed30f19c2397bcb70d5191d64d150f274))
* **design-pages:** put the wire types behind builders so they can grow ([#139](https://github.com/yschimke/compose-preview-daemon/issues/139)) ([a5cc051](https://github.com/yschimke/compose-preview-daemon/commit/a5cc0517010456a6ecea252a89c1f086dd8a3469))


### Bug Fixes

* **build:** gate the six published JVM modules a bad classifier missed ([#140](https://github.com/yschimke/compose-preview-daemon/issues/140)) ([7fc3e4a](https://github.com/yschimke/compose-preview-daemon/commit/7fc3e4a92b497a1433edac2369655e7bb6167632))
* **build:** stop lint checking a device API floor against host-JVM code ([#136](https://github.com/yschimke/compose-preview-daemon/issues/136)) ([9b5808d](https://github.com/yschimke/compose-preview-daemon/commit/9b5808d3bb5d1e75e5e5d8344670f6a87a0a19a4))
* **renderer:** emit catalog sidecars from SideEffect, not remember ([#137](https://github.com/yschimke/compose-preview-daemon/issues/137)) ([b944ee0](https://github.com/yschimke/compose-preview-daemon/commit/b944ee0958ec058cea0e5d8d6a557e1b80e13609))


### CI

* run Android lint on every pull request ([#138](https://github.com/yschimke/compose-preview-daemon/issues/138)) ([7cb1cc7](https://github.com/yschimke/compose-preview-daemon/commit/7cb1cc71ecf55de398266aa12461967556b3b865))


### Chores

* record published versions for v3.7.0 ([#135](https://github.com/yschimke/compose-preview-daemon/issues/135)) ([75f9c9a](https://github.com/yschimke/compose-preview-daemon/commit/75f9c9a45b159e086908999ee7529e98cd04b2fd))

## [3.7.0](https://github.com/yschimke/compose-preview-daemon/compare/v3.6.1...v3.7.0) (2026-09-16)


### Features

* **build:** gate the published ABI on every JVM and KMP module ([#130](https://github.com/yschimke/compose-preview-daemon/issues/130)) ([b77abcc](https://github.com/yschimke/compose-preview-daemon/commit/b77abcc9be9cd9f3a9288bc471b0a3a1e9fc3f14))


### Chores

* record published versions for v3.6.1 ([#133](https://github.com/yschimke/compose-preview-daemon/issues/133)) ([fd363fb](https://github.com/yschimke/compose-preview-daemon/commit/fd363fbb2840ea1e1612a4fb646506ad77a068a5))

## [3.6.1](https://github.com/yschimke/compose-preview-daemon/compare/v3.6.0...v3.6.1) (2026-09-16)


### Bug Fixes

* **design-pages:** keep an unrecognised enum a parse failure ([#128](https://github.com/yschimke/compose-preview-daemon/issues/128)) ([61a4d34](https://github.com/yschimke/compose-preview-daemon/commit/61a4d34906b60d350b0c1ab403ad004b27d10afd))

## [3.6.0](https://github.com/yschimke/compose-preview-daemon/compare/v3.5.0...v3.6.0) (2026-09-16)


### Features

* **build:** publish a BOM describing every daemon coordinate ([#122](https://github.com/yschimke/compose-preview-daemon/issues/122)) ([78bb41e](https://github.com/yschimke/compose-preview-daemon/commit/78bb41eaa3d142ef770e87c6e41270c8bfc5039a))
* **build:** publish only the modules a release changes ([#123](https://github.com/yschimke/compose-preview-daemon/issues/123)) ([63638da](https://github.com/yschimke/compose-preview-daemon/commit/63638da97f75c0a31e439114203d499501b2bf43))
* **fonts:** record the axes a resolution asked for and did not get ([#125](https://github.com/yschimke/compose-preview-daemon/issues/125)) ([71ead85](https://github.com/yschimke/compose-preview-daemon/commit/71ead85c74bf56b54580a3e5dc1b091712020e8e))

## [3.5.0](https://github.com/yschimke/compose-preview-daemon/compare/v3.4.9...v3.5.0) (2026-09-16)


### Features

* **design-pages:** shared background assets and allowlisted blend modes ([#119](https://github.com/yschimke/compose-preview-daemon/issues/119)) ([c577939](https://github.com/yschimke/compose-preview-daemon/commit/c577939ca1870d3fbc5746bf0c4eab5b3cd787f8))


### Bug Fixes

* **deps:** update compose-preview-contracts to v2.19.0 ([#118](https://github.com/yschimke/compose-preview-daemon/issues/118)) ([58fc973](https://github.com/yschimke/compose-preview-daemon/commit/58fc973206e95c4d4525013780378e646b0f1a67))
* **deps:** update dependency ee.schimke.composeai:third-party-rc-embedded-player to v1.63.0 ([#120](https://github.com/yschimke/compose-preview-daemon/issues/120)) ([e7e1b73](https://github.com/yschimke/compose-preview-daemon/commit/e7e1b730ef722253f5527ea9366122245a688a36))

## [3.4.9](https://github.com/yschimke/compose-preview-daemon/compare/v3.4.8...v3.4.9) (2026-09-15)


### CI

* fail when release-please cannot parse a commit message ([#116](https://github.com/yschimke/compose-preview-daemon/issues/116)) ([e084b6e](https://github.com/yschimke/compose-preview-daemon/commit/e084b6e765f321b45b02ad251cf5b97c00b702a6))

## [3.4.8](https://github.com/yschimke/compose-preview-daemon/compare/v3.4.7...v3.4.8) (2026-09-15)


### Bug Fixes

* model Glimmer live gaze without shrinking defaults ([#111](https://github.com/yschimke/compose-preview-daemon/issues/111)) ([c0bc949](https://github.com/yschimke/compose-preview-daemon/commit/c0bc949961fd57ef4b17d72e1c0ff3ae66d16dc0))

## [3.4.7](https://github.com/yschimke/compose-preview-daemon/compare/v3.4.6...v3.4.7) (2026-09-15)


### Bug Fixes

* keep Glimmer live previews initially unfocused ([#109](https://github.com/yschimke/compose-preview-daemon/issues/109)) ([70f207f](https://github.com/yschimke/compose-preview-daemon/commit/70f207f3a608d579861512238e5cac58d61553fb))

## [3.4.6](https://github.com/yschimke/compose-preview-daemon/compare/v3.4.5...v3.4.6) (2026-09-14)


### Bug Fixes

* stabilize Glimmer previews and Wear ambient rendering ([#107](https://github.com/yschimke/compose-preview-daemon/issues/107)) ([9b7fdf4](https://github.com/yschimke/compose-preview-daemon/commit/9b7fdf43864fe9eda3da68866ddb4010801556f7))


### Documentation

* research use cases for the render daemon ([#101](https://github.com/yschimke/compose-preview-daemon/issues/101)) ([df96a1c](https://github.com/yschimke/compose-preview-daemon/commit/df96a1c43e26e76dea2595b19988f264a93a6595))


### Build

* make automated Gradle lock portable ([#106](https://github.com/yschimke/compose-preview-daemon/issues/106)) ([19730c4](https://github.com/yschimke/compose-preview-daemon/commit/19730c4bb42141dbdaeb711b96850ef6bf28e6cd))
* throttle automated Gradle runs ([#105](https://github.com/yschimke/compose-preview-daemon/issues/105)) ([976cc85](https://github.com/yschimke/compose-preview-daemon/commit/976cc859536b29211c2062b7316774e5cee50cf2))

## [3.4.5](https://github.com/yschimke/compose-preview-daemon/compare/v3.4.4...v3.4.5) (2026-09-13)


### Bug Fixes

* **daemon:** delegate the choice-knob option type to the parent loader ([#99](https://github.com/yschimke/compose-preview-daemon/issues/99)) ([fe36def](https://github.com/yschimke/compose-preview-daemon/commit/fe36def3240e1485371509b12485bea51405d7df))

## [3.4.4](https://github.com/yschimke/compose-preview-daemon/compare/v3.4.3...v3.4.4) (2026-09-12)


### Bug Fixes

* **deps:** update compose-preview-contracts to v2.18.0 ([#96](https://github.com/yschimke/compose-preview-daemon/issues/96)) ([99c35fa](https://github.com/yschimke/compose-preview-daemon/commit/99c35fa0df2937d4e89452f2eb3b37fa8ba3bcf5))
* **deps:** update dependency ee.schimke.composeai:third-party-rc-embedded-player to v1.61.1 ([#97](https://github.com/yschimke/compose-preview-daemon/issues/97)) ([e02d014](https://github.com/yschimke/compose-preview-daemon/commit/e02d0145c464eb82a774cd8b7ae70f5be613fcf0))


### Chores

* drop the catalog entries the compose-ai-tools split left behind ([#95](https://github.com/yschimke/compose-preview-daemon/issues/95)) ([325b632](https://github.com/yschimke/compose-preview-daemon/commit/325b632ee0615ced94d4a9379e0b926689bf1ff8))

## [3.4.3](https://github.com/yschimke/compose-preview-daemon/compare/v3.4.2...v3.4.3) (2026-09-11)


### Bug Fixes

* correct activity chrome and root-relative bounds ([#93](https://github.com/yschimke/compose-preview-daemon/issues/93)) ([59a1ec2](https://github.com/yschimke/compose-preview-daemon/commit/59a1ec2e3fb3031e1e8518b7965c420e706e3e87))
* **deps:** update compose-ai-tools to v2.17.0 ([#91](https://github.com/yschimke/compose-preview-daemon/issues/91)) ([1cb3ee9](https://github.com/yschimke/compose-preview-daemon/commit/1cb3ee9a288e3c80599ce31df0eeda919f678d59))
* **deps:** update compose-preview-contracts to v2.17.0 ([#92](https://github.com/yschimke/compose-preview-daemon/issues/92)) ([0527044](https://github.com/yschimke/compose-preview-daemon/commit/05270445630032b678bd199eba7969acad4851f2))


### Documentation

* qualify worker memory policies under reloads ([#86](https://github.com/yschimke/compose-preview-daemon/issues/86)) ([ffb02d4](https://github.com/yschimke/compose-preview-daemon/commit/ffb02d431d7b3b1b0e1b06599af206cce3e67f22))

## [3.4.2](https://github.com/yschimke/compose-preview-daemon/compare/v3.4.1...v3.4.2) (2026-09-11)


### Performance

* bound compiler work in Android daemon launch plans ([#79](https://github.com/yschimke/compose-preview-daemon/issues/79)) ([d48dee4](https://github.com/yschimke/compose-preview-daemon/commit/d48dee41a0deec9bf5be7287a114f71bd8a0e837))
* bulk copy canonical ARGB settling snapshots ([#80](https://github.com/yschimke/compose-preview-daemon/issues/80)) ([0ded8ce](https://github.com/yschimke/compose-preview-daemon/commit/0ded8ce684705fc3d4b8b9560df4ee9b8df30f53))
* reuse owned pixel buffers during visual settling ([#88](https://github.com/yschimke/compose-preview-daemon/issues/88)) ([5d3a2e3](https://github.com/yschimke/compose-preview-daemon/commit/5d3a2e309dc930b6ca00a921816ec86ad0355da4))
* reuse the final settling pixel snapshot ([#76](https://github.com/yschimke/compose-preview-daemon/issues/76)) ([d8c4bbb](https://github.com/yschimke/compose-preview-daemon/commit/d8c4bbba35a45b4d91bc7275b1acada58a10fc63))
* support sampled post-GC render metrics ([#82](https://github.com/yschimke/compose-preview-daemon/issues/82)) ([f8ee665](https://github.com/yschimke/compose-preview-daemon/commit/f8ee665b6739e3bcbc66609ae4018d16c522abe9))


### Documentation

* measure C1 compilation across long reload sessions ([#89](https://github.com/yschimke/compose-preview-daemon/issues/89)) ([7897bc3](https://github.com/yschimke/compose-preview-daemon/commit/7897bc38bdb7913f4cbe7be81dc1e7dd68e09dfc))
* measure explicit GC under bounded Serial heaps ([#81](https://github.com/yschimke/compose-preview-daemon/issues/81)) ([7c98850](https://github.com/yschimke/compose-preview-daemon/commit/7c98850d6a3658cfe699880c9114a0dafe086c1b))
* measure periodic native trimming with sampled metrics ([#85](https://github.com/yschimke/compose-preview-daemon/issues/85)) ([e9ef8a0](https://github.com/yschimke/compose-preview-daemon/commit/e9ef8a0e3fe9906df32525963ac58c912104c496))
* recheck field caching under current worker profiles ([#87](https://github.com/yschimke/compose-preview-daemon/issues/87)) ([081eac9](https://github.com/yschimke/compose-preview-daemon/commit/081eac9946162bf05fda4c7b6d07dc1097edec47))
* validate compiler limits across reloads and concurrent workers ([#78](https://github.com/yschimke/compose-preview-daemon/issues/78)) ([5d14855](https://github.com/yschimke/compose-preview-daemon/commit/5d14855b6f51c29b348cbeb7e1ff6507607579e7))
* validate sampled metrics with concurrent workers ([#83](https://github.com/yschimke/compose-preview-daemon/issues/83)) ([d0db011](https://github.com/yschimke/compose-preview-daemon/commit/d0db011c4dea964281242af568ee9c59103a6409))

## [3.4.1](https://github.com/yschimke/compose-preview-daemon/compare/v3.4.0...v3.4.1) (2026-09-11)


### Bug Fixes

* **renderer:** hide host action bar before composition ([#74](https://github.com/yschimke/compose-preview-daemon/issues/74)) ([b12bbec](https://github.com/yschimke/compose-preview-daemon/commit/b12bbecc28187fa8f780c127ad48c5691124fd57))


### Performance

* settle still frames before PNG encoding ([#72](https://github.com/yschimke/compose-preview-daemon/issues/72)) ([271a8e7](https://github.com/yschimke/compose-preview-daemon/commit/271a8e7256159f0973a5e00d03377fbbc878f5e5))


### Documentation

* attribute reload memory and measure initial heap sizing ([#73](https://github.com/yschimke/compose-preview-daemon/issues/73)) ([26e6722](https://github.com/yschimke/compose-preview-daemon/commit/26e672264abdd9a7b8d2e4cc363a3bc39895f04b))

## [3.4.0](https://github.com/yschimke/compose-preview-daemon/compare/v3.3.0...v3.4.0) (2026-09-11)


### Features

* **preview-annotations:** add `related` to @CatalogComponent ([#62](https://github.com/yschimke/compose-preview-daemon/issues/62)) ([88bc484](https://github.com/yschimke/compose-preview-daemon/commit/88bc4849d525bbac53188328011118cd2094afc5))


### Bug Fixes

* keep discovery drains within their save boundary ([#65](https://github.com/yschimke/compose-preview-daemon/issues/65)) ([e9fb7bf](https://github.com/yschimke/compose-preview-daemon/commit/e9fb7bf0c2c0c554138675803a29051044a78fdd))


### Performance

* reduce capture CPU and release reloaded classes ([#60](https://github.com/yschimke/compose-preview-daemon/issues/60)) ([0d00f1d](https://github.com/yschimke/compose-preview-daemon/commit/0d00f1d56116a8aafbac71bb311dcd62b37e9f8d))
* reduce inspector CPU during repeated captures ([#66](https://github.com/yschimke/compose-preview-daemon/issues/66)) ([44617e3](https://github.com/yschimke/compose-preview-daemon/commit/44617e34d12d421be2c82769dcacd84ca95ded8f))
* reuse decoded still images for size correction ([#69](https://github.com/yschimke/compose-preview-daemon/issues/69)) ([027d678](https://github.com/yschimke/compose-preview-daemon/commit/027d6788e558c64e0af7b4d46356c558dba0a9de))


### Documentation

* prioritize Robolectric upstream feedback ([#64](https://github.com/yschimke/compose-preview-daemon/issues/64)) ([28e0f43](https://github.com/yschimke/compose-preview-daemon/commit/28e0f43d69581a34f0af140ff5ff66560269d9bf))
* record reload soak and explicit GC tradeoffs ([#61](https://github.com/yschimke/compose-preview-daemon/issues/61)) ([255730b](https://github.com/yschimke/compose-preview-daemon/commit/255730b403f545ab8e7ec147246eddc088d469b8))

## [3.3.0](https://github.com/yschimke/compose-preview-daemon/compare/v3.2.0...v3.3.0) (2026-09-11)


### Features

* **overrides:** publish the named-override runtime as Kotlin Multiplatform ([#58](https://github.com/yschimke/compose-preview-daemon/issues/58)) ([1eddb3f](https://github.com/yschimke/compose-preview-daemon/commit/1eddb3f024c459bb8ed3d08af515fd94c4daf05c))


### Bug Fixes

* **deps:** update compose-ai-tools to v2.16.0 ([#53](https://github.com/yschimke/compose-preview-daemon/issues/53)) ([a7272e3](https://github.com/yschimke/compose-preview-daemon/commit/a7272e399d89df3cea0922fb97b1035fd78fd11d))
* **deps:** update compose-preview-contracts to v2.16.0 ([#54](https://github.com/yschimke/compose-preview-daemon/issues/54)) ([a639d13](https://github.com/yschimke/compose-preview-daemon/commit/a639d139db09b0455a952eb4bcf4c015a2cd77d2))
* **deps:** update dependency org.robolectric:robolectric to v4.17 ([#51](https://github.com/yschimke/compose-preview-daemon/issues/51)) ([d7da242](https://github.com/yschimke/compose-preview-daemon/commit/d7da24219eda9ee81c9d363ee294dda309b7f4db))


### Performance

* **daemon:** benchmark startup tuning, bindings and CDS ([#49](https://github.com/yschimke/compose-preview-daemon/issues/49)) ([9a7d7b7](https://github.com/yschimke/compose-preview-daemon/commit/9a7d7b7cc5421c0963b2a8a9a64a009ddcc53d0f))
* **daemon:** measure startup overlap and loaded worker efficiency ([#56](https://github.com/yschimke/compose-preview-daemon/issues/56)) ([18b8237](https://github.com/yschimke/compose-preview-daemon/commit/18b82373ac1024eb603c95646c4927e2d6a27b95))


### Chores

* **deps:** update actions/setup-java action to v6 ([#55](https://github.com/yschimke/compose-preview-daemon/issues/55)) ([e0b94e5](https://github.com/yschimke/compose-preview-daemon/commit/e0b94e53e78ccb1a144733ca1e986cc8acad7902))
* **deps:** update actions/setup-java action to v6.0.1 ([#57](https://github.com/yschimke/compose-preview-daemon/issues/57)) ([a1c7963](https://github.com/yschimke/compose-preview-daemon/commit/a1c79630dec525ddf78a5d63dd8f71177ab83ae8))
* **deps:** update github-actions ([#52](https://github.com/yschimke/compose-preview-daemon/issues/52)) ([e513293](https://github.com/yschimke/compose-preview-daemon/commit/e513293075f6aea5fcf991f31b4aaf13f379cdd5))
* **deps:** update gradle to v9.7.1 ([#50](https://github.com/yschimke/compose-preview-daemon/issues/50)) ([29fc3cf](https://github.com/yschimke/compose-preview-daemon/commit/29fc3cfe8233cd9a1f1b8bde1c6cf2f67beec28e))

## [3.2.0](https://github.com/yschimke/compose-preview-daemon/compare/v3.1.0...v3.2.0) (2026-09-10)


### Features

* **client:** RobolectricLaunch, the renderer facts without a daemon ([#47](https://github.com/yschimke/compose-preview-daemon/issues/47)) ([d28d31e](https://github.com/yschimke/compose-preview-daemon/commit/d28d31e56fec2ea4da1c3ef411f0138de2254d53))

## [3.1.0](https://github.com/yschimke/compose-preview-daemon/compare/v3.0.3...v3.1.0) (2026-09-10)


### Features

* **client:** DaemonLaunchOptions, the writing half of the property registry ([#35](https://github.com/yschimke/compose-preview-daemon/issues/35)) ([5044447](https://github.com/yschimke/compose-preview-daemon/commit/50444478c52923dff3c2e1486a205d18d0e464b7))
* **client:** DaemonLaunchPlan, so the daemon says how to run itself ([#40](https://github.com/yschimke/compose-preview-daemon/issues/40)) ([cc26b1a](https://github.com/yschimke/compose-preview-daemon/commit/cc26b1ad1c41deb299eef05a2be14e5d6575e696))
* **client:** ManagedDaemon, one daemon's life and nothing more ([#45](https://github.com/yschimke/compose-preview-daemon/issues/45)) ([a8cd6b9](https://github.com/yschimke/compose-preview-daemon/commit/a8cd6b9ade84f5db1b4c1b1822d27be6d4f48f4c))
* **client:** publish DaemonSession, the protocol as an interface ([#33](https://github.com/yschimke/compose-preview-daemon/issues/33)) ([0de1086](https://github.com/yschimke/compose-preview-daemon/commit/0de10860157adbd6d46333836a34afc12ad5f7dd))


### Bug Fixes

* **build:** enforce daemon dependency ownership in CI ([#42](https://github.com/yschimke/compose-preview-daemon/issues/42)) ([37fb992](https://github.com/yschimke/compose-preview-daemon/commit/37fb99236430cc0732fc1caeb376bbdf7d70f261))
* **build:** refresh dependency locks after version bumps ([#43](https://github.com/yschimke/compose-preview-daemon/issues/43)) ([3fb26b9](https://github.com/yschimke/compose-preview-daemon/commit/3fb26b931763fd3ec875ed18c42a3d9252e32d59))
* **build:** remove inherited dependency locking ([#44](https://github.com/yschimke/compose-preview-daemon/issues/44)) ([765cf69](https://github.com/yschimke/compose-preview-daemon/commit/765cf69656cea87953bb4ed9ae13238d25a107e1))
* **deps:** update compose-ai-tools to v2.15.0 ([#37](https://github.com/yschimke/compose-preview-daemon/issues/37)) ([7c4cb11](https://github.com/yschimke/compose-preview-daemon/commit/7c4cb118b424879306518d8c558c86d6036c1a68))
* **deps:** update compose-preview-contracts to v2.15.0 ([#38](https://github.com/yschimke/compose-preview-daemon/issues/38)) ([2e27b3b](https://github.com/yschimke/compose-preview-daemon/commit/2e27b3b1d2b4cb4483e69b22f595a104c6e39fe8))
* **deps:** update dependency ee.schimke.composeai:compose-preview-serve to v3.23.0 ([#39](https://github.com/yschimke/compose-preview-daemon/issues/39)) ([8bebf11](https://github.com/yschimke/compose-preview-daemon/commit/8bebf11d43d930b844b52c6d7310805c0e65b099))
* **deps:** update rc-players to v1.60.2 ([#36](https://github.com/yschimke/compose-preview-daemon/issues/36)) ([fc59972](https://github.com/yschimke/compose-preview-daemon/commit/fc59972baf3db4df5ea2967cafad073024c78843))


### Performance

* **daemon:** profile startup and benchmark compiler tuning ([#34](https://github.com/yschimke/compose-preview-daemon/issues/34)) ([08d095e](https://github.com/yschimke/compose-preview-daemon/commit/08d095e4c842b708b159ce32cbc8e87652417305))
* **daemon:** prototype and measure activity-free capture ([#41](https://github.com/yschimke/compose-preview-daemon/issues/41)) ([5f9199f](https://github.com/yschimke/compose-preview-daemon/commit/5f9199fcfb917e421c20a171496744eb8b8c8306))


### Documentation

* **daemon:** handoff for the boot roadmap's Tier B (Robolectric fork) ([#27](https://github.com/yschimke/compose-preview-daemon/issues/27)) ([444fd87](https://github.com/yschimke/compose-preview-daemon/commit/444fd874576301eb02c9b1d1732644a078d4d41f))
* propose an embedding API for the daemon ([#31](https://github.com/yschimke/compose-preview-daemon/issues/31)) ([c7ff645](https://github.com/yschimke/compose-preview-daemon/commit/c7ff645b721d4e553a88802621ccf05f9565a56c))

## [3.0.3](https://github.com/yschimke/compose-preview-daemon/compare/v3.0.2...v3.0.3) (2026-09-10)


### Refactoring

* merge overrides onto the RenderSpec, not a separate DTO ([#28](https://github.com/yschimke/compose-preview-daemon/issues/28)) ([1892eda](https://github.com/yschimke/compose-preview-daemon/commit/1892eda74e463dbfa0eec8c8b59dd0786e8cf84f))

## [3.0.2](https://github.com/yschimke/compose-preview-daemon/compare/v3.0.1...v3.0.2) (2026-09-10)


### Bug Fixes

* **deps:** update androidx ([#16](https://github.com/yschimke/compose-preview-daemon/issues/16)) ([1b6111f](https://github.com/yschimke/compose-preview-daemon/commit/1b6111f421850d93984592b849ba4c19c249b330))
* **deps:** update androidx-compose ([#17](https://github.com/yschimke/compose-preview-daemon/issues/17)) ([a40b9a7](https://github.com/yschimke/compose-preview-daemon/commit/a40b9a714a029f19399eaddb8bcd604c06a583a8))
* **deps:** update androidx-wear to v1.7.0-rc01 ([#18](https://github.com/yschimke/compose-preview-daemon/issues/18)) ([157b1e2](https://github.com/yschimke/compose-preview-daemon/commit/157b1e2548686b4b11cd322c704230b7a49830ca))
* **deps:** update compose-ai-tools to v2.14.0 ([#21](https://github.com/yschimke/compose-preview-daemon/issues/21)) ([a05c130](https://github.com/yschimke/compose-preview-daemon/commit/a05c130e72c7429d588a3d32f2835fe302582eb9))
* **deps:** update compose-preview-contracts to v2.14.0 ([#22](https://github.com/yschimke/compose-preview-daemon/issues/22)) ([149dc57](https://github.com/yschimke/compose-preview-daemon/commit/149dc573eb4c546a5722e2d5ea0882432aac21eb))
* **deps:** update dependency ee.schimke.composeai:compose-preview-serve to v3.22.0 ([#23](https://github.com/yschimke/compose-preview-daemon/issues/23)) ([0058038](https://github.com/yschimke/compose-preview-daemon/commit/0058038c33bca8ad7cdc47bf6c5a1a20104215cf))
* **deps:** update kotlin to v2.4.20 ([#19](https://github.com/yschimke/compose-preview-daemon/issues/19)) ([0b7645d](https://github.com/yschimke/compose-preview-daemon/commit/0b7645d66f026fd8192a503f072853612ffa13e7))
* **deps:** update metro to v1.4.3 ([#20](https://github.com/yschimke/compose-preview-daemon/issues/20)) ([f386066](https://github.com/yschimke/compose-preview-daemon/commit/f3860668e5e900d710d489d648bb6aa2a0bfca36))
* **deps:** update rc-players to v1.60.1 ([#24](https://github.com/yschimke/compose-preview-daemon/issues/24)) ([d814536](https://github.com/yschimke/compose-preview-daemon/commit/d814536e460a54c89bfde51b878248a74658c4e9))


### Performance

* **daemon:** hand the encoded frame back from the desktop render ([#25](https://github.com/yschimke/compose-preview-daemon/issues/25)) ([4bbb34e](https://github.com/yschimke/compose-preview-daemon/commit/4bbb34e668bbf3ba4478c5ba4ac469861aa27999))


### Refactoring

* RenderResult carries a RenderArtifact instead of a pngPath string ([#13](https://github.com/yschimke/compose-preview-daemon/issues/13)) ([aae75ed](https://github.com/yschimke/compose-preview-daemon/commit/aae75ed034fdd077038f00ca433020af707b691e))


### CI

* retarget the Renovate config at this repository ([#15](https://github.com/yschimke/compose-preview-daemon/issues/15)) ([6fa111e](https://github.com/yschimke/compose-preview-daemon/commit/6fa111ebe907a49c032d71abb668fc171d74acd8))

## [3.0.1](https://github.com/yschimke/compose-preview-daemon/compare/v3.0.0...v3.0.1) (2026-09-10)


### Bug Fixes

* **figma-svg:** don't degrade an export that draws no text ([#11](https://github.com/yschimke/compose-preview-daemon/issues/11)) ([0104e4f](https://github.com/yschimke/compose-preview-daemon/commit/0104e4f6eadae6d98a72a865c02764c7e9ad56db))


### Performance

* **daemon:** adopt pre-booted spare sandbox workers instead of booting ([#8](https://github.com/yschimke/compose-preview-daemon/issues/8)) ([94fc815](https://github.com/yschimke/compose-preview-daemon/commit/94fc815855b0dddb87c166b595a5af57550a70b0))
* **daemon:** defer the in-process sandbox behind adopted spares, hand them back on shutdown, and prove it end to end ([#9](https://github.com/yschimke/compose-preview-daemon/issues/9)) ([d109aa7](https://github.com/yschimke/compose-preview-daemon/commit/d109aa775fb2865264c9e47001c8409f022117b8))


### Refactoring

* one shared override merge instead of six per-lane copies ([#10](https://github.com/yschimke/compose-preview-daemon/issues/10)) ([1494548](https://github.com/yschimke/compose-preview-daemon/commit/1494548440851a5f59a77e0fa1918976ad79d9cd))
* one typed RenderSpec and RenderTarget instead of a payload string ([#7](https://github.com/yschimke/compose-preview-daemon/issues/7)) ([290feb8](https://github.com/yschimke/compose-preview-daemon/commit/290feb8e993ff157e8236327531f4940ef73c44d))


### CI

* create the git tag for the draft release before publishing from it ([#5](https://github.com/yschimke/compose-preview-daemon/issues/5)) ([9faed3a](https://github.com/yschimke/compose-preview-daemon/commit/9faed3a75260485743b52771db4fb3ec1b488de3))

## [3.0.0](https://github.com/yschimke/compose-preview-daemon/compare/v2.4.1...v3.0.0) (2026-09-09)


### Features

* stand the repository up around the imported renderers, extractors and daemon ([a80e6a5](https://github.com/yschimke/compose-preview-daemon/commit/a80e6a51f508c4e3ed21c8fa112b8574c0b380e6))
* stand the repository up around the imported renderers, extractors and daemon ([bbdef71](https://github.com/yschimke/compose-preview-daemon/commit/bbdef71f928c9889cb8591358ce1bce53cd68c77))


### CI

* drop desktopTest from the module test fan-out ([41917ba](https://github.com/yschimke/compose-preview-daemon/commit/41917bab711102dff3d38c7f7c26fc050711796e))


### Chores

* release the first version from this repository as 3.0.0 ([cbfc494](https://github.com/yschimke/compose-preview-daemon/commit/cbfc494760da9370537726c7b9f973427080f589))
* release the first version from this repository as 3.0.0 ([50cab15](https://github.com/yschimke/compose-preview-daemon/commit/50cab15e3c3c3719561bf752d964033faf1cf7d6))
* start the changelog at the import, not at the imported history ([3c74631](https://github.com/yschimke/compose-preview-daemon/commit/3c746318f9a84d56bab1c69c38cfab8b6d09cb90))
