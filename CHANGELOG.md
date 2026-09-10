# Changelog

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
