# Changelog

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
