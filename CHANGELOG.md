# Changelog

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
