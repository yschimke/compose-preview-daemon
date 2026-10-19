# Changelog

## [3.18.9](https://github.com/yschimke/compose-preview-daemon/compare/v3.18.8...v3.18.9) (2026-10-19)


### Bug Fixes

* **deps:** update dependency ee.schimke.composeai:rc-players-bom to v2.16.1 ([#317](https://github.com/yschimke/compose-preview-daemon/issues/317)) ([07b1203](https://github.com/yschimke/compose-preview-daemon/commit/07b120396fefaebcb15b74e9cdf66fa4bf1d74ff))
* **deps:** update dependency io.github.classgraph:classgraph to v4.8.198 ([#319](https://github.com/yschimke/compose-preview-daemon/issues/319)) ([151b9e7](https://github.com/yschimke/compose-preview-daemon/commit/151b9e7b1c85442d4fed045b3c8f3e1df5b5ce4d))
* never give the render host activity a title bar ([#320](https://github.com/yschimke/compose-preview-daemon/issues/320)) ([3de45eb](https://github.com/yschimke/compose-preview-daemon/commit/3de45ebf12f07f29381560017989c4a89949ea16))

## [3.18.8](https://github.com/yschimke/compose-preview-daemon/compare/v3.18.7...v3.18.8) (2026-10-18)


### Bug Fixes

* **deps:** update dependency ee.schimke.composeai:rc-players-bom to v2.16.0 ([#316](https://github.com/yschimke/compose-preview-daemon/issues/316)) ([38c8493](https://github.com/yschimke/compose-preview-daemon/commit/38c8493d493356f248fff4596b28f860cf8e9be8))
* **release:** fail when Maven task planning fails ([#312](https://github.com/yschimke/compose-preview-daemon/issues/312)) ([2da42ee](https://github.com/yschimke/compose-preview-daemon/commit/2da42ee2e09ca6d66a19436c14f88b61eb9d9caf))
* **renderer:** hide the host action bar synchronously under edge-to-edge ([#315](https://github.com/yschimke/compose-preview-daemon/issues/315)) ([135fcc3](https://github.com/yschimke/compose-preview-daemon/commit/135fcc3db156ebdf8e02f0c837f03094d60b9fa7))

## [3.18.7](https://github.com/yschimke/compose-preview-daemon/compare/v3.18.6...v3.18.7) (2026-10-17)


### Bug Fixes

* **deps:** update dependency ee.schimke.composeai:compose-preview-contracts-bom to v3.36.0 ([#310](https://github.com/yschimke/compose-preview-daemon/issues/310)) ([259d0b2](https://github.com/yschimke/compose-preview-daemon/commit/259d0b2871fe512340ab111ea72514e7185af6c5))
* **deps:** update dependency ee.schimke.composeai:rc-players-bom to v2.14.0 ([#307](https://github.com/yschimke/compose-preview-daemon/issues/307)) ([32fa03d](https://github.com/yschimke/compose-preview-daemon/commit/32fa03d833737b2a73fe7fa40da0360c7985e853))
* **deps:** update dependency ee.schimke.composeai:rc-players-bom to v2.15.0 ([#311](https://github.com/yschimke/compose-preview-daemon/issues/311)) ([f5ac81a](https://github.com/yschimke/compose-preview-daemon/commit/f5ac81aad065303296b608a4c0de23bc8bbbbe16))
* **renderer:** retain defaults for instrumented static previews ([#309](https://github.com/yschimke/compose-preview-daemon/issues/309)) ([dcfabb3](https://github.com/yschimke/compose-preview-daemon/commit/dcfabb332beb08dc329b698fb6e9b61d021a498a))

## [3.18.6](https://github.com/yschimke/compose-preview-daemon/compare/v3.18.5...v3.18.6) (2026-10-17)


### Bug Fixes

* **daemon:** launch sandbox workers through a -D argfile and exit on a failed start ([#306](https://github.com/yschimke/compose-preview-daemon/issues/306)) ([089a29c](https://github.com/yschimke/compose-preview-daemon/commit/089a29c0de13ed8a273ca192cce59ad1ac778671))
* **deps:** update dependency ee.schimke.composeai:rc-players-bom to v2.13.1 ([#304](https://github.com/yschimke/compose-preview-daemon/issues/304)) ([1662b66](https://github.com/yschimke/compose-preview-daemon/commit/1662b666516ccee7bbee2ecfe1aa0b26a76045f4))


### CI

* dispatch CI and format checks on the release PR ([#303](https://github.com/yschimke/compose-preview-daemon/issues/303)) ([4d765ac](https://github.com/yschimke/compose-preview-daemon/commit/4d765acbd6767a01a558a94ac7b8321997012c17))

## [3.18.5](https://github.com/yschimke/compose-preview-daemon/compare/v3.18.4...v3.18.5) (2026-10-16)


### Bug Fixes

* **client:** pass oversized -D properties through an argfile ([#301](https://github.com/yschimke/compose-preview-daemon/issues/301)) ([5045b0c](https://github.com/yschimke/compose-preview-daemon/commit/5045b0cd3bb5ada8d6f1c4d738d7a5bf59e98559))

## [3.18.4](https://github.com/yschimke/compose-preview-daemon/compare/v3.18.3...v3.18.4) (2026-10-16)


### Bug Fixes

* **interactive:** resolve semantic targets and a11y actions against the merged tree ([#299](https://github.com/yschimke/compose-preview-daemon/issues/299)) ([45d7f29](https://github.com/yschimke/compose-preview-daemon/commit/45d7f2922e88eb523cd023e38f0d1791986434b2))

## [3.18.3](https://github.com/yschimke/compose-preview-daemon/compare/v3.18.2...v3.18.3) (2026-10-16)


### Bug Fixes

* **app-compose:** answer legacy android/support classes from the app's AndroidX jars ([#296](https://github.com/yschimke/compose-preview-daemon/issues/296)) ([e4ae8c3](https://github.com/yschimke/compose-preview-daemon/commit/e4ae8c3cafaf080b76188bdcc517785de169199c))


### CI

* **release:** publish the GitHub release only once Maven Central serves it ([#297](https://github.com/yschimke/compose-preview-daemon/issues/297)) ([bdd877d](https://github.com/yschimke/compose-preview-daemon/commit/bdd877d6e10f16012a43bbb3f95e11084b315f61))

## [3.18.2](https://github.com/yschimke/compose-preview-daemon/compare/v3.18.1...v3.18.2) (2026-10-16)


### Bug Fixes

* **deps:** update dependency ee.schimke.composeai:rc-players-bom to v2.13.0 ([#293](https://github.com/yschimke/compose-preview-daemon/issues/293)) ([9d62417](https://github.com/yschimke/compose-preview-daemon/commit/9d6241744bc37008cf99ef2abce2b0740d4df925))
* ship espresso-core 3.7.0 in the Android daemon sidecar so API 37 renders work ([#294](https://github.com/yschimke/compose-preview-daemon/issues/294)) ([48b9ab5](https://github.com/yschimke/compose-preview-daemon/commit/48b9ab562918752af49fac53d68459ccedc002be))

## [3.18.1](https://github.com/yschimke/compose-preview-daemon/compare/v3.18.0...v3.18.1) (2026-10-15)


### Bug Fixes

* **deps:** update dependency org.checkerframework:checker-qual to v4.3.1 ([#290](https://github.com/yschimke/compose-preview-daemon/issues/290)) ([b7bc400](https://github.com/yschimke/compose-preview-daemon/commit/b7bc400244e42a0c1fc1f099365edd792aaaab51))
* exit AndroidRendererMain after a successful render ([#292](https://github.com/yschimke/compose-preview-daemon/issues/292)) ([d0f747f](https://github.com/yschimke/compose-preview-daemon/commit/d0f747f99a932127f84cc961b9133a998fea90ae))

## [3.18.0](https://github.com/yschimke/compose-preview-daemon/compare/v3.17.1...v3.18.0) (2026-10-15)


### Features

* pin the wall clock the consumer's own code reads in Android renders ([#287](https://github.com/yschimke/compose-preview-daemon/issues/287)) ([e6815b2](https://github.com/yschimke/compose-preview-daemon/commit/e6815b21cd6b576a8535e4d885a641bc293145a1))


### Bug Fixes

* **deps:** update dependency ee.schimke.composeai:compose-preview-contracts-bom to v3.35.0 ([#289](https://github.com/yschimke/compose-preview-daemon/issues/289)) ([c3c2bb7](https://github.com/yschimke/compose-preview-daemon/commit/c3c2bb77e1cd03bcbce8c7662978f0a0a355b5e1))
* run the app's own Compose when the user classpath carries a newer one ([#288](https://github.com/yschimke/compose-preview-daemon/issues/288)) ([f19f397](https://github.com/yschimke/compose-preview-daemon/commit/f19f397838cdfeafbd8fbe309687e1ed5f02ec21))
* size unsized text at the frame's density in the figma SVG export ([#282](https://github.com/yschimke/compose-preview-daemon/issues/282)) ([0334f09](https://github.com/yschimke/compose-preview-daemon/commit/0334f09270ad390f43f8ca673348cbccb6d53b54))

## [3.17.1](https://github.com/yschimke/compose-preview-daemon/compare/v3.17.0...v3.17.1) (2026-10-15)


### Bug Fixes

* boot the daemon sandbox at the consumer's SDK, not always 35 ([#283](https://github.com/yschimke/compose-preview-daemon/issues/283)) ([9588c24](https://github.com/yschimke/compose-preview-daemon/commit/9588c24f7732ba08468c2552972ce666cef89a74))
* **deps:** update kotlin to v2.4.21 ([#284](https://github.com/yschimke/compose-preview-daemon/issues/284)) ([7cf7053](https://github.com/yschimke/compose-preview-daemon/commit/7cf705340b9b2c3424185365a2bc0291432db229))

## [3.17.0](https://github.com/yschimke/compose-preview-daemon/compare/v3.16.2...v3.17.0) (2026-10-15)


### Features

* raise the Robolectric SDK ceiling to API 37 ([#279](https://github.com/yschimke/compose-preview-daemon/issues/279)) ([e90e9e9](https://github.com/yschimke/compose-preview-daemon/commit/e90e9e939246c93426e73b6c4594844c9b6dabec))


### Bug Fixes

* **deps:** update androidx-wear to v1.7.1 ([#276](https://github.com/yschimke/compose-preview-daemon/issues/276)) ([a802cd6](https://github.com/yschimke/compose-preview-daemon/commit/a802cd6094fab0f3554f661fa19b5f2567ad623e))
* **deps:** update dependency androidx.collection:collection to v1.7.0 ([#277](https://github.com/yschimke/compose-preview-daemon/issues/277)) ([e271e8b](https://github.com/yschimke/compose-preview-daemon/commit/e271e8be9764f9b513597a44260d6513f81770b1))
* **deps:** update dependency ee.schimke.composeai:compose-preview-contracts-bom to v3.32.0 ([#272](https://github.com/yschimke/compose-preview-daemon/issues/272)) ([2b63a2b](https://github.com/yschimke/compose-preview-daemon/commit/2b63a2b95b02a7dcef218dc6ae37e0778ae9945c))
* **deps:** update dependency ee.schimke.composeai:compose-preview-contracts-bom to v3.33.0 ([#281](https://github.com/yschimke/compose-preview-daemon/issues/281)) ([ffb2dd7](https://github.com/yschimke/compose-preview-daemon/commit/ffb2dd78905bf46ebc746ba83a5d0a8f407aeb14))
* **deps:** update dependency io.github.classgraph:classgraph to v4.8.197 ([#271](https://github.com/yschimke/compose-preview-daemon/issues/271)) ([bacf394](https://github.com/yschimke/compose-preview-daemon/commit/bacf394218d767b7d9139638533fb00322b7deaf))


### CI

* **release:** ignore test source sets in the Maven publish plan ([#274](https://github.com/yschimke/compose-preview-daemon/issues/274)) ([6c8b6fa](https://github.com/yschimke/compose-preview-daemon/commit/6c8b6fa7d094d0cc5ec8d8709ad3a48e049fcffc))


### Chores

* **deps:** update actions/setup-node action to v7.1.0 ([#278](https://github.com/yschimke/compose-preview-daemon/issues/278)) ([a5c4f19](https://github.com/yschimke/compose-preview-daemon/commit/a5c4f19a1865e64c4baa92069a67369657892dfb))
* **deps:** update dependency yschimke/compose-preview-xr to v2.0.2 ([#280](https://github.com/yschimke/compose-preview-daemon/issues/280)) ([99f93bc](https://github.com/yschimke/compose-preview-daemon/commit/99f93bc521d48af6622213ad4e64e7411988f9e3))
* **deps:** update github-actions ([#270](https://github.com/yschimke/compose-preview-daemon/issues/270)) ([2afbcf7](https://github.com/yschimke/compose-preview-daemon/commit/2afbcf72c58a14d05c04cdd28f1180d69eea4764))
* **deps:** update gradle to v9.8.1 ([#275](https://github.com/yschimke/compose-preview-daemon/issues/275)) ([4a8274b](https://github.com/yschimke/compose-preview-daemon/commit/4a8274b1801abd5739107d67654b5c02f8e69d51))

## [3.16.2](https://github.com/yschimke/compose-preview-daemon/compare/v3.16.1...v3.16.2) (2026-10-14)


### Bug Fixes

* **android:** make the interactive-session idle-lease and carried-player tests deterministic ([#263](https://github.com/yschimke/compose-preview-daemon/issues/263)) ([3284af3](https://github.com/yschimke/compose-preview-daemon/commit/3284af300823a920c25f00d5514c4391c63dcb40))
* **deps:** update dependency ee.schimke.composeai:compose-preview-contracts-bom to v3.31.0 ([#269](https://github.com/yschimke/compose-preview-daemon/issues/269)) ([5c4e0be](https://github.com/yschimke/compose-preview-daemon/commit/5c4e0bedd2723958e4818e40bd1770177a148b98))

## [3.16.1](https://github.com/yschimke/compose-preview-daemon/compare/v3.16.0...v3.16.1) (2026-10-13)


### Bug Fixes

* **android:** let the View player's taps take effect in a held session ([#262](https://github.com/yschimke/compose-preview-daemon/issues/262)) ([276fa1c](https://github.com/yschimke/compose-preview-daemon/commit/276fa1cc11e125397e64d26be0f63380e94778f9))
* **core:** keep the read loop up through a classpath-dirty exit ([#264](https://github.com/yschimke/compose-preview-daemon/issues/264)) ([2ab67e2](https://github.com/yschimke/compose-preview-daemon/commit/2ab67e2138e8089d79d25e5c1ae88fab9cd476c3))
* **deps:** update dependency ee.schimke.composeai:rc-players-bom to v2.10.0 ([#259](https://github.com/yschimke/compose-preview-daemon/issues/259)) ([3102693](https://github.com/yschimke/compose-preview-daemon/commit/3102693fa633f1b62ba332014c28e7e5c1f14de8))
* **deps:** update dependency org.jetbrains.skiko:skiko-awt to v0.153.0 ([#267](https://github.com/yschimke/compose-preview-daemon/issues/267)) ([cc482be](https://github.com/yschimke/compose-preview-daemon/commit/cc482beac9c526ca43a4aab6e6628e232b493aca))
* **figma-svg:** audit emitted text before reporting lost font families ([#266](https://github.com/yschimke/compose-preview-daemon/issues/266)) ([e29463c](https://github.com/yschimke/compose-preview-daemon/commit/e29463c1016b9136127ee11c15fbb2945c998948))


### CI

* **renovate:** propose updates on every run instead of weekly ([#260](https://github.com/yschimke/compose-preview-daemon/issues/260)) ([4184267](https://github.com/yschimke/compose-preview-daemon/commit/41842672bdf9e8d5c2f8915beab7071a2f5d2c98))
* run policy checks for workflow-opened release PRs ([#265](https://github.com/yschimke/compose-preview-daemon/issues/265)) ([1808a38](https://github.com/yschimke/compose-preview-daemon/commit/1808a387f2863fe95b18317b01fc03222272439e))

## [3.16.0](https://github.com/yschimke/compose-preview-daemon/compare/v3.15.4...v3.16.0) (2026-10-12)


### Features

* **android:** play a carried Remote Compose document in the live session ([#254](https://github.com/yschimke/compose-preview-daemon/issues/254)) ([4df5241](https://github.com/yschimke/compose-preview-daemon/commit/4df52411b72c778597e76d362e3a6da0502c218f))
* **desktop:** play a carried Remote Compose document in the CMP JVM player ([#255](https://github.com/yschimke/compose-preview-daemon/issues/255)) ([00c930a](https://github.com/yschimke/compose-preview-daemon/commit/00c930a0e5e73155416ff09dc5d78cc9f3489510))

## [3.15.4](https://github.com/yschimke/compose-preview-daemon/compare/v3.15.3...v3.15.4) (2026-10-12)


### Bug Fixes

* **deps:** update dependency ee.schimke.composeai:compose-preview-contracts-bom to v3.30.0 ([#244](https://github.com/yschimke/compose-preview-daemon/issues/244)) ([fb9f465](https://github.com/yschimke/compose-preview-daemon/commit/fb9f465eb14d4b89f4dfef08ec624588032d6dc1))
* **deps:** update dependency ee.schimke.composeai:rc-players-bom to v2.7.1 ([#245](https://github.com/yschimke/compose-preview-daemon/issues/245)) ([30efb31](https://github.com/yschimke/compose-preview-daemon/commit/30efb3163b512beb7b3f0193148e383d81bc0084))
* **deps:** update dependency ee.schimke.composeai:rc-players-bom to v2.8.0 ([#249](https://github.com/yschimke/compose-preview-daemon/issues/249)) ([622bbdc](https://github.com/yschimke/compose-preview-daemon/commit/622bbdc5dcf6ebac6cc82c147870054795556a72))
* **deps:** update dependency ee.schimke.composeai:rc-players-bom to v2.9.0 ([#253](https://github.com/yschimke/compose-preview-daemon/issues/253)) ([2a6ba5b](https://github.com/yschimke/compose-preview-daemon/commit/2a6ba5b387155a4791e1dbea1fc9833b569007a7))
* **deps:** update dependency org.checkerframework:checker-qual to v4.3.0 ([#250](https://github.com/yschimke/compose-preview-daemon/issues/250)) ([6e3d9cd](https://github.com/yschimke/compose-preview-daemon/commit/6e3d9cd1356e94b314eeae499d9799b102ba37cd))
* **deps:** update dependency org.ow2.asm:asm to v9.11 ([#251](https://github.com/yschimke/compose-preview-daemon/issues/251)) ([640675b](https://github.com/yschimke/compose-preview-daemon/commit/640675b0de841deda12bdd9ba1e21a1977a77009))
* **deps:** update dependency org.ow2.asm:asm-commons to v9.11 ([#252](https://github.com/yschimke/compose-preview-daemon/issues/252)) ([03763b7](https://github.com/yschimke/compose-preview-daemon/commit/03763b7c6a323accb03aea99026ee0d1f0da02eb))


### Performance

* **desktop:** load the preview index once, run only requested extensions, and time renders from dequeue ([#247](https://github.com/yschimke/compose-preview-daemon/issues/247)) ([2e7b785](https://github.com/yschimke/compose-preview-daemon/commit/2e7b785375f9f134c760aa0540bdbd9c55cf7677))


### Chores

* **deps:** update dependency yschimke/compose-preview-xr to v2.0.1 ([#248](https://github.com/yschimke/compose-preview-daemon/issues/248)) ([103ba11](https://github.com/yschimke/compose-preview-daemon/commit/103ba1182d1c0486662448b447817103d8f6d576))

## [3.15.3](https://github.com/yschimke/compose-preview-daemon/compare/v3.15.2...v3.15.3) (2026-10-11)


### Bug Fixes

* **deps:** update dependency ee.schimke.composeai:compose-preview-contracts-bom to v3.26.0 ([#239](https://github.com/yschimke/compose-preview-daemon/issues/239)) ([07669b3](https://github.com/yschimke/compose-preview-daemon/commit/07669b3cb3a71e32ae04ef07f8f3422d1cc4c28d))
* **deps:** update dependency ee.schimke.composeai:compose-preview-contracts-bom to v3.29.0 ([#242](https://github.com/yschimke/compose-preview-daemon/issues/242)) ([2eac369](https://github.com/yschimke/compose-preview-daemon/commit/2eac369f86570d7b54dbc12b0725f9953499d38c))
* **deps:** update dependency ee.schimke.composeai:rc-players-bom to v2.5.0 ([#241](https://github.com/yschimke/compose-preview-daemon/issues/241)) ([d403369](https://github.com/yschimke/compose-preview-daemon/commit/d403369fa05063c18bb079c0b79f225e6af87ca6))


### Documentation

* require explicit user requests for merging PRs ([#238](https://github.com/yschimke/compose-preview-daemon/issues/238)) ([b99d195](https://github.com/yschimke/compose-preview-daemon/commit/b99d195f0044691ee5d29416ae046802bc970528))


### CI

* skip the heavy CI lanes on release-please PRs ([#243](https://github.com/yschimke/compose-preview-daemon/issues/243)) ([bfc12c0](https://github.com/yschimke/compose-preview-daemon/commit/bfc12c057706fac5f26b3b3689f64e7f01bacb48))

## [3.15.2](https://github.com/yschimke/compose-preview-daemon/compare/v3.15.1...v3.15.2) (2026-10-10)


### Bug Fixes

* **deps:** update dependency ee.schimke.composeai:compose-preview-contracts-bom to v3.25.0 ([#236](https://github.com/yschimke/compose-preview-daemon/issues/236)) ([de8e3f5](https://github.com/yschimke/compose-preview-daemon/commit/de8e3f50f2fed1924029f9c1f08fe065efb562a3))
* **renderer-desktop:** stop raising the consumer's material3 ([#235](https://github.com/yschimke/compose-preview-daemon/issues/235)) ([9403983](https://github.com/yschimke/compose-preview-daemon/commit/94039837509fe74acbab2626f1939bfe95b226b0))

## [3.15.1](https://github.com/yschimke/compose-preview-daemon/compare/v3.15.0...v3.15.1) (2026-10-09)


### Bug Fixes

* **deps:** contracts 3.24.0 ([#234](https://github.com/yschimke/compose-preview-daemon/issues/234)) ([fce1c90](https://github.com/yschimke/compose-preview-daemon/commit/fce1c90a0614a7cc251bd951a69c41a1dd4652ac))


### Build

* **deps:** rc-players 2.4.0 ([#232](https://github.com/yschimke/compose-preview-daemon/issues/232)) ([45dc54c](https://github.com/yschimke/compose-preview-daemon/commit/45dc54c7b3a7417ff3ba971c75c60d6b6cebd43b))

## [3.15.0](https://github.com/yschimke/compose-preview-daemon/compare/v3.14.1...v3.15.0) (2026-10-09)


### Features

* replay a Remote Compose document carried on renderNow ([#231](https://github.com/yschimke/compose-preview-daemon/issues/231)) ([2fe4da4](https://github.com/yschimke/compose-preview-daemon/commit/2fe4da4afb0b72409e873d7f141af224495fd3bc))


### Bug Fixes

* **deps:** update dependency ee.schimke.composeai:rc-players-bom to v2.2.1 ([#229](https://github.com/yschimke/compose-preview-daemon/issues/229)) ([70651ed](https://github.com/yschimke/compose-preview-daemon/commit/70651ed74d1af6ba3dd074bb2ad35c8c95d770e7))

## [3.14.1](https://github.com/yschimke/compose-preview-daemon/compare/v3.14.0...v3.14.1) (2026-10-07)


### Bug Fixes

* **deps:** rc-players 2.2.0, which applies a CoreText style's font features ([#227](https://github.com/yschimke/compose-preview-daemon/issues/227)) ([ab74939](https://github.com/yschimke/compose-preview-daemon/commit/ab749397258554adeb9f8adab3afe08e2df274e3))
* **deps:** update dependency ee.schimke.composeai:compose-preview-contracts-bom to v3.20.0 ([#228](https://github.com/yschimke/compose-preview-daemon/issues/228)) ([95ca3aa](https://github.com/yschimke/compose-preview-daemon/commit/95ca3aa91b26d2e08568bf1b30005a296b13374e))
* stop translucent recording GIFs smearing; remove dead code and trim comments ([#223](https://github.com/yschimke/compose-preview-daemon/issues/223)) ([591f01b](https://github.com/yschimke/compose-preview-daemon/commit/591f01b8da15bf2a476064ab8d9e865559fc5af4))


### Documentation

* update agent plugin location and setup guidance ([#222](https://github.com/yschimke/compose-preview-daemon/issues/222)) ([808dd81](https://github.com/yschimke/compose-preview-daemon/commit/808dd8156d916272d890fb81125ca48ad5356b57))


### CI

* restore the Gradle and Robolectric caches instead of downloading every run ([#226](https://github.com/yschimke/compose-preview-daemon/issues/226)) ([d6e733a](https://github.com/yschimke/compose-preview-daemon/commit/d6e733a3fcf6210d3ab0e55e39730898d49b3a38))
* run daemon Android tests two forks at a time, on their own runner ([#225](https://github.com/yschimke/compose-preview-daemon/issues/225)) ([255a451](https://github.com/yschimke/compose-preview-daemon/commit/255a451682d99a401e98f60aac1ce2db6792eb04))


### Chores

* align daemon dependencies to latest release BOMs ([124af53](https://github.com/yschimke/compose-preview-daemon/commit/124af5385d83eb7ca1c918416d3bd2b65e399ab7))
* **deps:** update dependency dev.zacsweers.metro to v1.4.5 ([#220](https://github.com/yschimke/compose-preview-daemon/issues/220)) ([5f06036](https://github.com/yschimke/compose-preview-daemon/commit/5f06036c3ecc360dab87ef030a15242319563797))

## [3.14.0](https://github.com/yschimke/compose-preview-daemon/compare/v3.13.2...v3.14.0) (2026-10-04)


### Features

* **daemon:** boot worker sandboxes on demand ([#219](https://github.com/yschimke/compose-preview-daemon/issues/219)) ([435fba9](https://github.com/yschimke/compose-preview-daemon/commit/435fba9caf75fbba6e2d3aaaa57002fcef81292a))


### Bug Fixes

* **deps:** rc-players 2.1.2 ([#217](https://github.com/yschimke/compose-preview-daemon/issues/217)) ([caf2157](https://github.com/yschimke/compose-preview-daemon/commit/caf2157fd50ee1ecc17c1f6850a2bf4bdf2b9ed4))

## [3.13.2](https://github.com/yschimke/compose-preview-daemon/compare/v3.13.1...v3.13.2) (2026-10-03)


### Bug Fixes

* **client:** stop evicting warm spares for every new signature ([#215](https://github.com/yschimke/compose-preview-daemon/issues/215)) ([6f86bf8](https://github.com/yschimke/compose-preview-daemon/commit/6f86bf8e7b9381e910dea208a8df9c88d69e7139))
* **pool:** give a worker no CDS archive when its classpath has a non-empty directory ([#213](https://github.com/yschimke/compose-preview-daemon/issues/213)) ([19b9e8d](https://github.com/yschimke/compose-preview-daemon/commit/19b9e8dd175c49b3de9e63db734600c2720d6ee3))
* **renderer-desktop:** give previews a Dispatchers.Main on the EDT ([#214](https://github.com/yschimke/compose-preview-daemon/issues/214)) ([42fa23a](https://github.com/yschimke/compose-preview-daemon/commit/42fa23ac768df4cfcbf03026145dae78d80941ad))


### Build

* **deps:** resolve contracts and rc-players through their BOMs ([#211](https://github.com/yschimke/compose-preview-daemon/issues/211)) ([cc4e738](https://github.com/yschimke/compose-preview-daemon/commit/cc4e7386f62dbafe7b356d3f5163b159296a6dcb))

## [3.13.1](https://github.com/yschimke/compose-preview-daemon/compare/v3.13.0...v3.13.1) (2026-10-03)


### Bug Fixes

* give cmp-android the pinned Robolectric clock the AndroidX backends get ([#209](https://github.com/yschimke/compose-preview-daemon/issues/209)) ([58419ab](https://github.com/yschimke/compose-preview-daemon/commit/58419abe9e66bb8fb32f3ac86cac701967d201ab))

## [3.13.0](https://github.com/yschimke/compose-preview-daemon/compare/v3.12.1...v3.13.0) (2026-10-02)


### Features

* **data-motion:** changed-region APNG frames with per-frame delays ([#207](https://github.com/yschimke/compose-preview-daemon/issues/207)) ([a7e00ec](https://github.com/yschimke/compose-preview-daemon/commit/a7e00ec38e5e9ec81a072ea6f22e72808e7d316c))
* **renderer-android:** honour @AnimatedPreview format = Apng ([#208](https://github.com/yschimke/compose-preview-daemon/issues/208)) ([43f5ce2](https://github.com/yschimke/compose-preview-daemon/commit/43f5ce2329da24abb0a92f03a46b53f8b3884fd2))


### Bug Fixes

* **renderer-android:** advance animated and interaction captures by the exact frame interval ([#206](https://github.com/yschimke/compose-preview-daemon/issues/206)) ([ef0e415](https://github.com/yschimke/compose-preview-daemon/commit/ef0e4158e6d71faf812e022502d62717fe5d59dc))
* **renderer:** distribute GIF frame-delay rounding so playback keeps the captured timeline ([#204](https://github.com/yschimke/compose-preview-daemon/issues/204)) ([0283241](https://github.com/yschimke/compose-preview-daemon/commit/028324142ff0da13d6864b72cf10dcae23f1da7c))

## [3.12.1](https://github.com/yschimke/compose-preview-daemon/compare/v3.12.0...v3.12.1) (2026-10-02)


### Bug Fixes

* **ci:** open the next release PR only after the release is published ([#202](https://github.com/yschimke/compose-preview-daemon/issues/202)) ([1c785ff](https://github.com/yschimke/compose-preview-daemon/commit/1c785ffd95421e35d8df7500aa7e20b094e71a9a))
* **renderer-desktop:** advance animated captures by the exact frame interval ([#203](https://github.com/yschimke/compose-preview-daemon/issues/203)) ([16899b8](https://github.com/yschimke/compose-preview-daemon/commit/16899b82ac25c2e58451d144ceb2d173a4f9842a))


### CI

* **release:** comment the Maven publish plan on the release PR ([#201](https://github.com/yschimke/compose-preview-daemon/issues/201)) ([4ea3d7d](https://github.com/yschimke/compose-preview-daemon/commit/4ea3d7deadfd27fc93208d255dfd7536c6458345))

## [3.12.0](https://github.com/yschimke/compose-preview-daemon/compare/v3.11.0...v3.12.0) (2026-10-02)


### Features

* **release:** only count build inputs that can change an artifact as shared ([#193](https://github.com/yschimke/compose-preview-daemon/issues/193)) ([60ee7cd](https://github.com/yschimke/compose-preview-daemon/commit/60ee7cdea0fda32df021c817096e744f2a705278))
* **release:** treat sibling coordinates as floors in the publish plan ([#194](https://github.com/yschimke/compose-preview-daemon/issues/194)) ([aba8848](https://github.com/yschimke/compose-preview-daemon/commit/aba8848f366110b590ddb4b7bcae6f652f2f51af))
* **remotecompose:** honour the wire's playerId on replay ([#195](https://github.com/yschimke/compose-preview-daemon/issues/195)) ([1fcd152](https://github.com/yschimke/compose-preview-daemon/commit/1fcd152d5ceb912621df5c4dd1346b2c7f99b532))
* **remotecompose:** name players by implementation and add cmp-android, the CMP player on Android ([#199](https://github.com/yschimke/compose-preview-daemon/issues/199)) ([e87a03c](https://github.com/yschimke/compose-preview-daemon/commit/e87a03c45cc0c735f6b1c616dbde0d53bdaf39ad))
* **remotecompose:** put players behind a linkage-checked backend seam ([#192](https://github.com/yschimke/compose-preview-daemon/issues/192)) ([bafcf72](https://github.com/yschimke/compose-preview-daemon/commit/bafcf72cef56489d193df43a7237081d2b80abc2))


### Bug Fixes

* **daemon:** queue an override render resubmitted on renderFinished ([#196](https://github.com/yschimke/compose-preview-daemon/issues/196)) ([48dec57](https://github.com/yschimke/compose-preview-daemon/commit/48dec575d5b97e473e42a064d3787c5f167b74d1))
* **distribution:** stop shipping Remote Compose in the Android daemon sidecar ([#191](https://github.com/yschimke/compose-preview-daemon/issues/191)) ([b98980e](https://github.com/yschimke/compose-preview-daemon/commit/b98980e10e07b46e7deb46387a9d2f827bbe2568))
* **renderer-desktop:** drive desktop scenes on the EDT to stop RectList corruption ([#198](https://github.com/yschimke/compose-preview-daemon/issues/198)) ([f997981](https://github.com/yschimke/compose-preview-daemon/commit/f997981f35990d0b27a87751814b9607d6beeaf7))


### Refactoring

* **daemon:** split the connector SPI into daemon-connector-api ([#197](https://github.com/yschimke/compose-preview-daemon/issues/197)) ([362b326](https://github.com/yschimke/compose-preview-daemon/commit/362b326c201025dae52eb85814d7765af5cce1e1))

## [3.11.0](https://github.com/yschimke/compose-preview-daemon/compare/v3.10.1...v3.11.0) (2026-10-01)


### Features

* report each render's work trace on renderFinished ([#189](https://github.com/yschimke/compose-preview-daemon/issues/189)) ([d63eb5f](https://github.com/yschimke/compose-preview-daemon/commit/d63eb5f8be14e9e3e07e51999068ba48535679f6))


### Bug Fixes

* **remotecompose:** call the embedded player directly, with no silent view-player fallback ([#188](https://github.com/yschimke/compose-preview-daemon/issues/188)) ([66d2047](https://github.com/yschimke/compose-preview-daemon/commit/66d204720b18e1ede9af26811cb899fd26fe57b4))

## [3.10.1](https://github.com/yschimke/compose-preview-daemon/compare/v3.10.0...v3.10.1) (2026-09-30)


### Bug Fixes

* **deps:** update rc-players to v2.0.3 ([#186](https://github.com/yschimke/compose-preview-daemon/issues/186)) ([0d8f99c](https://github.com/yschimke/compose-preview-daemon/commit/0d8f99c5e1d60c5b752fe1076ea019230bb361e8))


### Chores

* **deps:** bump compose-preview-contracts to 3.13.0 ([#185](https://github.com/yschimke/compose-preview-daemon/issues/185)) ([44a94f6](https://github.com/yschimke/compose-preview-daemon/commit/44a94f6cee1e093d514fa5cb0cb116c70b7786c1))

## [3.10.0](https://github.com/yschimke/compose-preview-daemon/compare/v3.9.2...v3.10.0) (2026-09-30)


### Features

* seed every named-value override on the embedded Remote Compose player ([#183](https://github.com/yschimke/compose-preview-daemon/issues/183)) ([923a727](https://github.com/yschimke/compose-preview-daemon/commit/923a727d433fa2d57c44602c6e102aa87af8e082))


### Bug Fixes

* **deps:** update androidx ([#181](https://github.com/yschimke/compose-preview-daemon/issues/181)) ([44d0657](https://github.com/yschimke/compose-preview-daemon/commit/44d06578d49f10acd9cd73814774f60f85a3974a))
* **deps:** update androidx-wear to v1.7.0 ([#176](https://github.com/yschimke/compose-preview-daemon/issues/176)) ([6533f58](https://github.com/yschimke/compose-preview-daemon/commit/6533f5859caa9b551c398bf95e8a20690b8e05f3))
* **deps:** update compose-preview-contracts to v3.5.0 ([#170](https://github.com/yschimke/compose-preview-daemon/issues/170)) ([f1a6198](https://github.com/yschimke/compose-preview-daemon/commit/f1a61981b6fbcc92a8f1231f8a2552fdd1d07f75))
* **deps:** update compose-preview-contracts to v3.7.0 ([#177](https://github.com/yschimke/compose-preview-daemon/issues/177)) ([75a7dc0](https://github.com/yschimke/compose-preview-daemon/commit/75a7dc082d50ece1b13658052c324e658340c469))
* **deps:** update dependency com.android.tools.build:gradle to v9.4.1 ([#169](https://github.com/yschimke/compose-preview-daemon/issues/169)) ([a70333c](https://github.com/yschimke/compose-preview-daemon/commit/a70333c3bb33ce345e4696c304322dbe410738b2))
* **deps:** update dependency ee.schimke.composeai:third-party-rc-embedded-player to v1.69.0 ([#171](https://github.com/yschimke/compose-preview-daemon/issues/171)) ([181506b](https://github.com/yschimke/compose-preview-daemon/commit/181506b7bf4df5f632207eea0d0cc62b828dac34))
* **deps:** update dependency ee.schimke.composeai:third-party-rc-embedded-player to v1.70.0 ([#178](https://github.com/yschimke/compose-preview-daemon/issues/178)) ([8789630](https://github.com/yschimke/compose-preview-daemon/commit/87896304f989edf8cda44611b8fc2e061b8fd827))
* **deps:** update dependency io.github.classgraph:classgraph to v4.8.196 ([#173](https://github.com/yschimke/compose-preview-daemon/issues/173)) ([b568fa8](https://github.com/yschimke/compose-preview-daemon/commit/b568fa8f30469b7147843b75660450d6a478e22b))
* **deps:** update rc-players to v2 and seed embedded colours through RcPlayerState ([#182](https://github.com/yschimke/compose-preview-daemon/issues/182)) ([bccdac0](https://github.com/yschimke/compose-preview-daemon/commit/bccdac0d587dcd05103a83117e140fbc61b81a2a))
* **deps:** update roborazzi to v1.75.0 ([#172](https://github.com/yschimke/compose-preview-daemon/issues/172)) ([f08e66b](https://github.com/yschimke/compose-preview-daemon/commit/f08e66b674d693d5bcd9aef8b1599bb566b10a31))
* **deps:** update roborazzi to v1.76.0 ([#179](https://github.com/yschimke/compose-preview-daemon/issues/179)) ([75d9148](https://github.com/yschimke/compose-preview-daemon/commit/75d9148b27514d76643aaaff2f94e43ea62f253f))


### Chores

* **deps:** update agp to v9.4.1 ([#167](https://github.com/yschimke/compose-preview-daemon/issues/167)) ([2bf7eb3](https://github.com/yschimke/compose-preview-daemon/commit/2bf7eb3eb1729ac48f7e756235acc3f37928263e))
* **deps:** update dependency dev.zacsweers.metro to v1.4.4 ([#168](https://github.com/yschimke/compose-preview-daemon/issues/168)) ([b4df16a](https://github.com/yschimke/compose-preview-daemon/commit/b4df16a3f191672b61863b1aa4754d493b0da4de))
* **deps:** update github-actions ([#174](https://github.com/yschimke/compose-preview-daemon/issues/174)) ([d38656e](https://github.com/yschimke/compose-preview-daemon/commit/d38656e6875c87ad1d5fad32d8c270d38f1e36fa))
* **deps:** update gradle to v9.8.0 ([#175](https://github.com/yschimke/compose-preview-daemon/issues/175)) ([25c8760](https://github.com/yschimke/compose-preview-daemon/commit/25c8760e6ca1f379c72ff6c0e2f29f441c576ee6))

## [3.9.2](https://github.com/yschimke/compose-preview-daemon/compare/v3.9.1...v3.9.2) (2026-09-27)


### Bug Fixes

* drop missing compile-classpath dirs so the in-process compile runs ([#165](https://github.com/yschimke/compose-preview-daemon/issues/165)) ([e00b314](https://github.com/yschimke/compose-preview-daemon/commit/e00b31421e698d9502567c15dabd1c60860ed114))

## [3.9.1](https://github.com/yschimke/compose-preview-daemon/compare/v3.9.0...v3.9.1) (2026-09-27)


### Performance

* run the figma-svg export only when its kind is requested ([#163](https://github.com/yschimke/compose-preview-daemon/issues/163)) ([529eab6](https://github.com/yschimke/compose-preview-daemon/commit/529eab64afd9cee69d60b9860b9fdec71b765b58))

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
