package ee.schimke.composeai.designpages

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `DesignPagesManifest` — whole **pages of a design file**, cached as SVG, with the node id of
 * every component on them joined back to the code that implements it.
 *
 * A design file's pages are its specimen sheets: the shape set, the type scale, every button
 * variant, laid out the way the designer means them to be read. Caching one as SVG rather than as a
 * raster is what makes this more than a screenshot — an SVG exported with node ids is a *document a
 * consumer can address*. Given a node id, the preview server finds that shape in the markup, hides
 * the design's own drawing of it, and puts this catalog's render in the hole it leaves. Same sheet,
 * same layout, our pixels.
 *
 * ## What this replaced, and why the shape changed
 *
 * Version 1 was a **foreign** contract: design-parity's `@design-parity/page-backdrop`, which
 * imported one composed *screen* as a flat PNG and carried a rectangle per component instance on
 * it. It is retired here rather than extended, for a reason that is a property of the data and not
 * of the code: in the Material 3 kit exactly one page holds instances at all, most of each screen
 * on it is hand-drawn rather than assembled from the kit, and the densest screen in the file yields
 * eleven placements of which two are OS chrome. The definition sheets — the other thirty pages —
 * are where the design actually says what a component should look like.
 *
 * So version 2 is **first-party**: this repo's own producer writes it
 * (`scripts/design-artifacts/emit-design-pages.mjs`, from a repo's committed import), and this file
 * is the contract rather than a mirror of someone else's. Two shape changes fall out of that:
 *
 * - **An image is an SVG, not a raster.** [PageImage.format] exists to say so out loud rather than
 *   to offer a choice; a consumer that cannot address the markup gets nothing this surface is for.
 * - **A node carries no bounding box.** Version 1 had to — a flat raster has no structure to ask.
 *   An SVG does, and the element's own box is the answer. Recording Figma's `absoluteBoundingBox`
 *   alongside would give one question two answers that disagree by a few pixels on anything with a
 *   shadow (the export box is the *render* box, effect bleed included), and a consumer choosing
 *   between them would silently pick the wrong one.
 *
 * Version 1 manifests are **not** read. They describe a surface that no longer exists, and their
 * PNGs would paint a stage with nothing addressable on it; [supportsDesignPagesVersion] refuses
 * them so a stale delivery branch shows no pages rather than a page that does nothing.
 *
 * ## A page is a stack of layers, and 2 still covers it
 *
 * A page is no longer one drawing. It is, bottom to top: shared raster plates
 * ([DesignPage.background], resolved through [DesignPagesManifest.assets]), the sanitized design
 * SVG over them at [DesignPage.designBlend], and this catalog's renders in the holes it leaves at
 * [DesignPage.renderBlend].
 *
 * That is a bigger change than it looks and it is still **version 2**, on purpose. Every field it
 * adds carries a default that reproduces the old behaviour exactly — no plates, everything
 * `source-over` — so a manifest written before any of it existed parses into the same stack it
 * always described, and an older server reading a newer manifest ignores the plates rather than
 * refusing the page. Bumping the version would instead make every already-published delivery branch
 * unreadable on the day this released, to describe a superset of what they already say.
 */
public const val DESIGN_PAGES_VERSION: Int = 2

/**
 * Whether a manifest at [version] is one we can read.
 *
 * Exact, unlike the range check the foreign v1 contract needed: the producer is in this repository
 * and ships in the same release as its consumer, so "newer than us" is not a state that can arise
 * from someone else's release cadence. Additive fields still parse — that is [DesignPagesJson]'s
 * job, not the version's.
 */
public fun supportsDesignPagesVersion(version: Int): Boolean = version == DESIGN_PAGES_VERSION

/**
 * The decoder every consumer of this contract should use.
 *
 * `ignoreUnknownKeys` because a manifest is read from a **delivery branch**, which is regenerated
 * on its own schedule and can easily be newer than the server reading it. Refusing to parse a
 * manifest that grew a field would turn every additive release into an outage on the last-deployed
 * server.
 */
public val DesignPagesJson: Json = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
}

/**
 * How a layer composites with what is already painted beneath it — a **closed allowlist**, not a
 * CSS passthrough.
 *
 * A design page is assembled from layers now rather than being one flat drawing: shared background
 * plates underneath ([DesignPage.background]), the sanitized design SVG over them, and this
 * catalog's own renders in the holes it leaves. The moment those are separate layers, *how* they
 * combine stops being implicit — and it is not always `source-over`. The Material 3 Glimmer kit's
 * Buttons sheet is the case that named this: its component sets are authored `mix-blend-mode:
 * screen` over image backplates, so compositing them opaquely over a pale fallback fill washes the
 * whole sheet toward white.
 *
 * **An enum, and never a string that reaches CSS.** The markup around these layers is inlined into
 * a served page, and a manifest comes off a delivery branch this server did not write. A `blend`
 * field carrying arbitrary text would be a style-injection route straight through the one surface
 * the SVG sanitizer exists to guard. Every value here is one a consumer has vetted, and [css] — not
 * the serial name, and never the manifest's own bytes — is what a renderer may emit.
 *
 * Adding a mode is deliberately a change to this file: a design system that needs `overlay` gets it
 * by someone reasoning about it here, not by writing it into a JSON file on a branch.
 *
 * An unrecognised value is a **parse failure for the whole manifest**, exactly as it is for
 * [PageNodeLink] — the contract's one rule for its enums, not a special case invented here. That is
 * the harsher outcome and the right one: the alternative is guessing what an unknown mode means
 * while compositing a layer the reader will believe. A producer is expected to refuse an unvetted
 * value before it is ever written (`design-pages.mjs` does), so a manifest carrying one is a
 * hand-edit or a newer producer, and both are better surfaced than silently reinterpreted. An
 * *additive* producer change carries new fields, which [DesignPagesJson] ignores.
 */
@Serializable
public enum class PageBlendMode {
  /**
   * Ordinary painting: the layer covers what is under it. The default everywhere, and the value a
   * manifest that says nothing means.
   */
  @SerialName("source-over") SOURCE_OVER,

  /** What Figma authors as `screen` — the Glimmer kit's component sets. Never darkens. */
  @SerialName("screen") SCREEN,

  /** Figma's `multiply`. Never lightens; the usual shadow/ink plate. */
  @SerialName("multiply") MULTIPLY,

  /**
   * Additive light.
   *
   * Distinct from [SCREEN] and not interchangeable with it, which is the whole reason the design
   * layer and the render layer carry separate modes ([DesignPage.designBlend],
   * [DesignPage.renderBlend]). Figma's Buttons page *authors* screen blending; an emissive capture
   * of the same components is additive radiance. Compositing each the way it was actually produced
   * is what makes the two lanes comparable pixel for pixel — forcing one mode on both would make
   * the diff a measurement of the mistake.
   */
  @SerialName("plus-lighter") PLUS_LIGHTER;

  /**
   * The CSS `mix-blend-mode` keyword for this mode.
   *
   * Separate from the serial name on purpose. The wire spells the default `source-over`, after the
   * Porter-Duff operator, because that is what it *is* and what a non-CSS consumer (a Skia or
   * Canvas compositor) needs to hear; CSS spells the same thing `normal`. Mapping here rather than
   * at each call site means a renderer emits a vetted keyword without ever touching the manifest's
   * own bytes.
   */
  public val css: String
    get() =
      when (this) {
        SOURCE_OVER -> "normal"
        SCREEN -> "screen"
        MULTIPLY -> "multiply"
        PLUS_LIGHTER -> "plus-lighter"
      }
}

/** How a node on the page was linked to code. */
@Serializable
public enum class PageNodeLink {
  /** Figma Code Connect — the machine link. */
  @SerialName("code-connect") CODE_CONNECT,

  /** An explicit entry in the repo's `design-map.json`. */
  @SerialName("manifest") MANIFEST,

  /** Best-effort name match; always low confidence. */
  @SerialName("convention") CONVENTION,

  /**
   * Nothing matched. Not an omission — a component on a specimen sheet with no code behind it is
   * the finding a whole-page view exists to surface, so the producer keeps it.
   */
  @SerialName("unlinked") UNLINKED,
}

/** How much to trust a node's [PageNode.code]. */
@Serializable
public enum class PageNodeConfidence {
  @SerialName("high") HIGH,
  @SerialName("low") LOW,
}

/** One addressable component node on the page, and the code it maps to. */
@Serializable
@ConsistentCopyVisibility
public data class PageNode
internal constructor(
  /**
   * The node's id in the design file — and the `data-node-id` attribute the export carries for it.
   *
   * This is the whole join. Everything else on this record is a label; this is the handle a
   * consumer uses to find the node in the SVG and take it out of the picture.
   */
  val nodeId: String,
  /** The node's layer name, e.g. `"Shape=Circle"`. Free text authored in the design tool. */
  val name: String = "",
  /** Nesting depth below the page, `1` for a direct child. A layout hint, nothing more. */
  val depth: Int = 0,
  /**
   * The node's own design ref, `"figma:<fileKey>/<nodeId>"` — what lets a node deep-link into the
   * design tool even where no code implements it. Prefer [DesignPagesManifest.refFor], which fills
   * the gap from the manifest's `fileKey` for a producer that wrote none.
   */
  val ref: String? = null,
  /** Code handle, e.g. `"catalog/…/Shapes.kt#CircleShape"`. Null when [link] is [UNLINKED]. */
  val code: String? = null,
  /**
   * Serve preview id, when the repo's `design-map.json` named one. This is what lets us draw the
   * component ourselves — at the node's own size, in the theme the visitor picked — rather than
   * show a baked screenshot.
   */
  val previewId: String? = null,
  val link: PageNodeLink = PageNodeLink.UNLINKED,
  /** Null when unlinked. Stated by the producer so we don't hardcode which methods are weak. */
  val confidence: PageNodeConfidence? = null,
  /**
   * This node is a GROUPING whose contents are listed below it — a Figma `COMPONENT_SET`, whose
   * children are the variants a definition sheet is a grid of.
   *
   * Stated by the producer rather than worked out here, because only the import has the real tree.
   * A manifest lists components and nothing else, so an unlisted frame between two of them lets a
   * shallower node be followed by a deeper one that is NOT inside it — and depth ordering alone
   * would call the shallower one a grouping. Nothing implements a component set (a reference names
   * one of its variants), so it is drawn as structure and left out of the coverage count.
   */
  val container: Boolean = false,
  /**
   * The node type reported by the design tool, for example `COMPONENT` or `COMPONENT_SET`.
   *
   * Kept as free text so a new design-tool type remains an additive manifest change. Consumers only
   * attach meaning to the grouping type they understand; see [isContainer].
   */
  val type: String? = null,
  /**
   * Whether this node is part of the design system's **published inventory** — something a catalog
   * could be expected to implement.
   *
   * `false` is for the kit's own internals: the base parts each published set is assembled from
   * (`Base / SelectionControl / Switch`, `Base / Loading Icon`), which a consumer of the kit never
   * places and no catalog owes an implementation. They are the same kind of thing as [isPrivate],
   * reached by a different convention — the Material 3 Expressive Wear kit states them by a `Base
   * /` name prefix rather than by Figma's leading dot — and `kit-sets.json` in the catalog repos
   * already excludes both from the kit walk. Counting them made a Buttons sheet report 24 missing
   * components that nothing could ever clear.
   *
   * **Stated by the producer, never inferred here**, for the same reason [container] is: the flat
   * node list has no ancestors, so a consumer cannot tell which set a `Selected=Yes, Disabled=No`
   * variant came out of. The importer walks the real tree and knows; see the note on [coverageGaps]
   * about why the depth-ordering shortcut is unsound in exactly the direction that hides a gap.
   *
   * Defaults to `true`, so every manifest published before this field existed keeps counting
   * exactly what it counted before.
   */
  val inventory: Boolean = true,
  /**
   * This node is drawn by an **override cell** rather than by a preview written for it — a
   * `_VARIANT_<name>` capture of some other preview with knobs seeded, not a `@Preview` of its own.
   *
   * The distinction the page needs and [link] cannot make: `link` says *how* the join was found
   * (Code Connect, `design-map.json`, a name match), and a cell is found the same ways anything
   * else is. What differs is what is behind it. A component someone sat down and wrote is not the
   * same claim as a kit variant reached by turning `checked` off on a neighbour — and once a sheet
   * is fully covered the two are indistinguishable on a page painted one colour, which is exactly
   * when the difference is most worth seeing.
   *
   * Deliberately NOT a fifth [PageNodeLink]. Cell-ness is orthogonal to link method — a cell can be
   * reached by Code Connect too — so folding them into one enum would lose the method in order to
   * record the cell, and a consumer asking "how do we know this maps?" would get "it's a variant"
   * back.
   *
   * Read off the **declared** preview id, which carries discovery's `_VARIANT_<name>` suffix, and
   * stated by the producer for the usual reason: the serve preview id a consumer sees is derived
   * from the published image path and need not keep the suffix.
   */
  val cell: Boolean = false,
) {
  /**
   * Builds a [PageNode].
   *
   * The way to construct one: [PageNode]'s own constructor is `internal`, and with
   * `@ConsistentCopyVisibility` so is its generated `copy`. Neither is public ABI any more, so
   * adding a property here cannot remove a signature a precompiled consumer already calls -- which
   * is exactly what `NoSuchMethodError: DesignPage.copy$default(...)` was.
   *
   * The rule that keeps that true: **a new property is always optional**, so it only ever adds a
   * setter here and never a parameter to this constructor. A property that genuinely cannot have a
   * default is a new type, not a new parameter.
   */
  public class Builder(nodeId: String) {
    public var nodeId: String = nodeId
    public var name: String = ""
    public var depth: Int = 0
    public var ref: String? = null
    public var code: String? = null
    public var previewId: String? = null
    public var link: PageNodeLink = PageNodeLink.UNLINKED
    public var confidence: PageNodeConfidence? = null
    public var container: Boolean = false
    public var type: String? = null
    public var inventory: Boolean = true
    public var cell: Boolean = false

    public fun build(): PageNode =
      PageNode(
        nodeId,
        name,
        depth,
        ref,
        code,
        previewId,
        link,
        confidence,
        container,
        type,
        inventory,
        cell,
      )
  }

  /** This [PageNode] as a [Builder], for deriving a modified one. Replaces `copy`. */
  public fun newBuilder(): Builder =
    Builder(nodeId).also {
      it.name = name
      it.depth = depth
      it.ref = ref
      it.code = code
      it.previewId = previewId
      it.link = link
      it.confidence = confidence
      it.container = container
      it.type = type
      it.inventory = inventory
      it.cell = cell
    }

  /**
   * A component the design file marks as **private** — Figma's leading-dot convention, used for the
   * internal furniture of a sheet: `.Header`, `.Legend`, the swatch a specimen grid repeats.
   *
   * Private components are not published to the design system's consumers, so no catalog is
   * expected to implement one, and counting them as missing coverage makes a complete sheet look
   * incomplete. They stay on the page and stay addressable — they are just not gaps.
   */
  public val isPrivate: Boolean
    get() = name.startsWith(".")

  /**
   * Whether this is a family/grouping rather than one concrete component.
   *
   * Current imports preserve Figma's exact `COMPONENT_SET` type. [container] remains as the
   * backwards-compatible producer hint, but reading [type] is essential: otherwise a whole grid of
   * variants becomes one enormous "missing component" hotspot over the actual components inside it.
   */
  public val isContainer: Boolean
    get() = container || type.equals("COMPONENT_SET", ignoreCase = true)

  /**
   * A **placement** of a component rather than a definition of one — Figma's `INSTANCE`.
   *
   * A specimen sheet is a grid of definitions with placements scattered around it: the page's own
   * `Header`, the `Toolbar` and `FAB` an example composition is assembled from, the `Side Sheet`
   * drawn beside the variant grid that defines it. None of those is a thing a catalog can implement
   * — you implement the component, and an instance only points at one — so an unlinked placement is
   * never a gap.
   *
   * Dropping one is not a blind spot, because a placement is not where a kit *states* a component.
   * A specimen sheet states its components as `COMPONENT` / `COMPONENT_SET`, and those are counted
   * on their own; the instances scattered around them are the page header, the parts of an example
   * composition, and the illustration beside a variant grid. The kit's Sheets page shows the last
   * of those plainly: four `Side Sheet` instances sit beside the `Side Sheet` `COMPONENT_SET` whose
   * four variants are already counted, so counting both reported one missing component twice. Its
   * Toolbars page shows the middle one: the `Toolbar`+`FAB` pairs are a demo of a toolbar with a
   * button beside it, and the toolbars themselves are stated — and implemented — further up.
   *
   * This deliberately does NOT try to prove the definition exists before dropping the placement.
   * The definition is often on another sheet (`Scrim` is drawn on Sheets and defined on Utilities),
   * so a same-page lookup would be wrong; and matching a placement to its definition by layer name
   * is the class of guess this whole change exists to remove — the leading-dot rule it replaces
   * failed exactly because a name is not a fact. Figma's own answer is an `INSTANCE`'s
   * `componentId`, which the manifest does not carry yet; recording it is what would turn this from
   * a sound default into a decision per node.
   *
   * Until then the exposure is bounded and one-directional: a component the kit shows ONLY as an
   * instance stops being counted, rather than being counted as done. The numerator cannot move —
   * across the whole Material 3 kit this changes the total on 27 sheets and the implemented count
   * on none of them.
   *
   * A **linked** instance is the exception and stays a component. Naming an instance's node id in
   * `design-map.json` is a deliberate claim that this placement is the thing we draw, and the
   * Snackbar sheet does exactly that for six of its ten snackbars. So only [isComponent] reads
   * this, and only together with [isUnlinked]: the node's type never overrides an authored mapping.
   */
  public val isPlacement: Boolean
    get() = type.equals("INSTANCE", ignoreCase = true)

  /**
   * A concrete, public component that the page should count and highlight.
   *
   * Four kinds of node are not one, and on a real specimen sheet they are most of it: the sheet's
   * private furniture ([isPrivate]), the kit's own base parts ([inventory] `= false`), the variant
   * sets ([isContainer]), and the placements no mapping claims ([isPlacement]). Everything the page
   * draws a mark for — the outline, the hit area, the audit row, the coverage tally — starts here,
   * so a node excluded here is not merely uncounted: it stops being something the reader can point
   * at, which is the right outcome for all four.
   */
  public val isComponent: Boolean
    get() = inventory && !isPrivate && !isContainer && !(isPlacement && isUnlinked)

  /** Whether this node can be drawn by us, i.e. it names a preview we could ask for. */
  public val isRenderable: Boolean
    get() = previewId != null

  /** True when nothing in the repo claims this part of the sheet. The interesting case. */
  public val isUnlinked: Boolean
    get() = link == PageNodeLink.UNLINKED

  /**
   * The preview this node may be drawn with, or null.
   *
   * Gated on the **link** as well as the id, deliberately. A manifest can carry `link: unlinked`
   * alongside a stale `previewId`, and drawing that would put a render on a node the same page
   * marks "no code behind this" — the two halves of the page contradicting each other. The link is
   * the claim; the id is only how to draw it.
   */
  public val renderablePreviewId: String?
    get() = if (isUnlinked) null else previewId
}

/** The cached export of a page. */
@Serializable
@ConsistentCopyVisibility
public data class PageImage
internal constructor(
  /** Path to the SVG, relative to the manifest file. */
  val uri: String,
  /**
   * Always `"svg"` today, and stated rather than assumed: a consumer that meets some other format
   * must refuse the page outright rather than inline bytes it cannot address, which is exactly what
   * a defaulted-and-unchecked field would let it do by accident.
   */
  val format: String = SVG,
) {
  /**
   * Builds a [PageImage].
   *
   * The way to construct one: [PageImage]'s own constructor is `internal`, and with
   * `@ConsistentCopyVisibility` so is its generated `copy`. Neither is public ABI any more, so
   * adding a property here cannot remove a signature a precompiled consumer already calls -- which
   * is exactly what `NoSuchMethodError: DesignPage.copy$default(...)` was.
   *
   * The rule that keeps that true: **a new property is always optional**, so it only ever adds a
   * setter here and never a parameter to this constructor. A property that genuinely cannot have a
   * default is a new type, not a new parameter.
   */
  public class Builder(uri: String) {
    public var uri: String = uri
    public var format: String = SVG

    public fun build(): PageImage = PageImage(uri, format)
  }

  /** This [PageImage] as a [Builder], for deriving a modified one. Replaces `copy`. */
  public fun newBuilder(): Builder = Builder(uri).also { it.format = format }

  public companion object {
    public const val SVG: String = "svg"
  }
}

/**
 * A **shared raster asset** — a backplate stored once in the bundle and referenced by however many
 * pages place it.
 *
 * ## Why a design page needs one at all
 *
 * A specimen sheet's heavy imagery and its addressable drawing pull in opposite directions. The
 * drawing has to stay an SVG — the node ids are the whole join, so a consumer can hide the design's
 * own rendering of a component and put a catalog render in its place. The imagery is photographic
 * or gradient backplate, which inlines into that SVG as base64 and blows past the page-size cap.
 *
 * Until now the only lever was to delete the backdrop (`excludeNodes` in the importer), and for a
 * backdrop-dependent sheet that is not a size optimisation, it is a correctness bug: the Glimmer
 * kit's Buttons page composites `screen`-blended component sets over five image backplates, and
 * with the plates pruned those components land on a pale fallback fill and wash toward white. The
 * page got smaller by losing the thing the retained drawing was drawn against.
 *
 * Storing the plate *beside* the SVG rather than inside it separates the two concerns: the page
 * keeps every node id and stays interactive, the bytes are carried once, and a plate repeated
 * across sections or pages resolves to one stored object.
 *
 * ## Content-addressed, and why that is load-bearing
 *
 * [id] is the SHA-256 of the encoded bytes, which buys three things at once: **deduplication** is
 * automatic (identical bytes have identical ids, so the five instances of one plate are one file),
 * **cache keys are immutable** (a published URL for `<id>` can never mean different bytes, so a
 * public server may serve it with an unbounded max-age), and **integrity is checkable** (a consumer
 * that hashes what it read has already verified it).
 *
 * ## Inert formats only
 *
 * [format] admits raster only. An SVG here would be markup a consumer never walked, reintroducing —
 * underneath the sanitized layer, where it is least visible — exactly what the sanitizer exists to
 * stop. Same reasoning as the `data:image/svg+xml` refusal in the SVG lane: an allowlist that stops
 * at the first hop is not one.
 *
 * [width], [height] and [bytes] are stated rather than discovered so a consumer can refuse an asset
 * **before** decoding it. A 64 KB PNG declaring 40000×40000 is a decompression bomb, and finding
 * that out from the decoder is finding it out too late; see [isWellFormed].
 */
@Serializable
@ConsistentCopyVisibility
public data class PageAsset
internal constructor(
  /** Lowercase hex SHA-256 of the encoded bytes. Also the basename under the assets directory. */
  val id: String,
  /** Path to the file, relative to the manifest — conventionally `assets/<id>.<ext>`. */
  val uri: String,
  /** One of [PNG], [JPEG], [WEBP]. Raster only; see the class comment. */
  val format: String,
  /** Decoded width in pixels, as stated by the producer. */
  val width: Int,
  /** Decoded height in pixels, as stated by the producer. */
  val height: Int,
  /** Encoded size in bytes, as stated by the producer. */
  val bytes: Long,
) {
  /**
   * Builds a [PageAsset].
   *
   * The way to construct one: [PageAsset]'s own constructor is `internal`, and with
   * `@ConsistentCopyVisibility` so is its generated `copy`. Neither is public ABI any more, so
   * adding a property here cannot remove a signature a precompiled consumer already calls -- which
   * is exactly what `NoSuchMethodError: DesignPage.copy$default(...)` was.
   *
   * The rule that keeps that true: **a new property is always optional**, so it only ever adds a
   * setter here and never a parameter to this constructor. A property that genuinely cannot have a
   * default is a new type, not a new parameter.
   */
  public class Builder(
    id: String,
    uri: String,
    format: String,
    width: Int,
    height: Int,
    bytes: Long,
  ) {
    public var id: String = id
    public var uri: String = uri
    public var format: String = format
    public var width: Int = width
    public var height: Int = height
    public var bytes: Long = bytes

    public fun build(): PageAsset = PageAsset(id, uri, format, width, height, bytes)
  }

  /** This [PageAsset] as a [Builder], for deriving a modified one. Replaces `copy`. */
  public fun newBuilder(): Builder = Builder(id, uri, format, width, height, bytes).also {}

  /**
   * Whether this record is one a consumer should even open the file for.
   *
   * Cheap, total, and deliberately checked against the **declaration** rather than the file: it
   * runs before any I/O, so a bomb is refused without being decoded. A consumer still verifies the
   * bytes it actually read (signature, real dimensions, the hash against [id]) — this is the first
   * gate, not the only one.
   *
   * Path safety is NOT checked here, because "safe relative path" is a property of the consumer's
   * filesystem and its staging root, not of the contract; every reader already has that check for
   * the SVG lane and applies the same one here.
   */
  public val isWellFormed: Boolean
    get() =
      SHA256_HEX.matches(id) &&
        uri.isNotBlank() &&
        format.lowercase() in FORMATS &&
        width in 1..MAX_ASSET_DIMENSION &&
        height in 1..MAX_ASSET_DIMENSION &&
        width.toLong() * height.toLong() <= MAX_ASSET_PIXELS &&
        bytes in 1..MAX_ASSET_BYTES

  public companion object {
    public const val PNG: String = "png"
    public const val JPEG: String = "jpeg"
    public const val WEBP: String = "webp"

    /** The inert raster formats a shared background may be. See the class comment. */
    public val FORMATS: Set<String> = setOf(PNG, JPEG, WEBP)

    /**
     * Encoded-byte ceiling for one asset.
     *
     * Generous — a full-bleed backplate for a 5000px-wide specimen sheet is megabytes — but not
     * absent, and separate from the SVG lane's own limit because the two fail differently: an
     * oversized SVG costs parse time, an oversized raster costs decoded heap.
     */
    public const val MAX_ASSET_BYTES: Long = 24L * 1024 * 1024

    /** Longest permitted side, decoded. Above this nothing is a backplate, it is a mistake. */
    public const val MAX_ASSET_DIMENSION: Int = 16384

    /**
     * Total decoded pixels. The limit that actually bounds memory: 4 bytes a pixel makes this ~256
     * MB decoded, and a pair of sides each under [MAX_ASSET_DIMENSION] can still multiply out to
     * far more than a server should be asked to hold.
     */
    public const val MAX_ASSET_PIXELS: Long = 64L * 1024 * 1024

    private val SHA256_HEX = Regex("[0-9a-f]{64}")
  }
}

/**
 * How a shared asset is **placed** on one page: where it sits, how it fills its box, and how it
 * composites.
 *
 * Separate from [PageAsset] because the bytes and the placement have different lifetimes and
 * different cardinalities — one stored plate, many placements, each with its own box. That split is
 * what lets the Buttons page keep five backplates while carrying the imagery once.
 *
 * Coordinates are in the page's own space, the one [PageFrame] describes and the SVG's `viewBox`
 * defines, so a placement lines up with the drawing above it without a consumer having to know the
 * export's scale. This is also why the code, design and diff lanes cannot drift apart: they read
 * the same placements in the same space, so switching lanes changes the component source and
 * nothing beneath it.
 */
@Serializable
@ConsistentCopyVisibility
public data class PageLayerPlacement
internal constructor(
  /** [PageAsset.id] of the asset to draw. A placement naming no stored asset is dropped. */
  val asset: String,
  val x: Double = 0.0,
  val y: Double = 0.0,
  val width: Double,
  val height: Double,
  /** Authored layer opacity, `0.0`–`1.0`. Out-of-range values are clamped by [isWellFormed]. */
  val opacity: Double = 1.0,
  /** One of [COVER], [CONTAIN], [FILL] — how the asset fills a box of a different aspect ratio. */
  val fit: String = COVER,
  /** Corner radius in page units, for a plate the design clips. */
  val radius: Double = 0.0,
  /** Whether the asset is clipped to its box. Off only for a plate that deliberately bleeds. */
  val clip: Boolean = true,
  /** How this plate composites with whatever is already beneath it. See [PageBlendMode]. */
  val blend: PageBlendMode = PageBlendMode.SOURCE_OVER,
) {
  /**
   * Builds a [PageLayerPlacement].
   *
   * The way to construct one: [PageLayerPlacement]'s own constructor is `internal`, and with
   * `@ConsistentCopyVisibility` so is its generated `copy`. Neither is public ABI any more, so
   * adding a property here cannot remove a signature a precompiled consumer already calls -- which
   * is exactly what `NoSuchMethodError: DesignPage.copy$default(...)` was.
   *
   * The rule that keeps that true: **a new property is always optional**, so it only ever adds a
   * setter here and never a parameter to this constructor. A property that genuinely cannot have a
   * default is a new type, not a new parameter.
   */
  public class Builder(asset: String, width: Double, height: Double) {
    public var asset: String = asset
    public var width: Double = width
    public var height: Double = height
    public var x: Double = 0.0
    public var y: Double = 0.0
    public var opacity: Double = 1.0
    public var fit: String = COVER
    public var radius: Double = 0.0
    public var clip: Boolean = true
    public var blend: PageBlendMode = PageBlendMode.SOURCE_OVER

    public fun build(): PageLayerPlacement =
      PageLayerPlacement(asset, x, y, width, height, opacity, fit, radius, clip, blend)
  }

  /** This [PageLayerPlacement] as a [Builder], for deriving a modified one. Replaces `copy`. */
  public fun newBuilder(): Builder =
    Builder(asset, width, height).also {
      it.x = x
      it.y = y
      it.opacity = opacity
      it.fit = fit
      it.radius = radius
      it.clip = clip
      it.blend = blend
    }

  /** Whether the box is drawable at all: finite, positive, and with a usable opacity and fit. */
  public val isWellFormed: Boolean
    get() =
      asset.isNotBlank() &&
        x.isFinite() &&
        y.isFinite() &&
        width.isFinite() &&
        width > 0.0 &&
        height.isFinite() &&
        height > 0.0 &&
        opacity.isFinite() &&
        opacity in 0.0..1.0 &&
        radius.isFinite() &&
        radius >= 0.0 &&
        fit.lowercase() in FITS

  public companion object {
    public const val COVER: String = "cover"
    public const val CONTAIN: String = "contain"
    public const val FILL: String = "fill"

    public val FITS: Set<String> = setOf(COVER, CONTAIN, FILL)
  }
}

/**
 * The page's coordinate space, read off the exported SVG's own `viewBox`.
 *
 * Taken from the export rather than computed from the node tree precisely so that the number a
 * consumer lays its stage out with is the number the picture was drawn at.
 */
@Serializable
@ConsistentCopyVisibility
public data class PageFrame internal constructor(val width: Double, val height: Double) {
  /**
   * Builds a [PageFrame]. See [DesignPage.Builder] for why the constructor is not public.
   *
   * A frame is a width and a height and will not grow, but it carries a Builder anyway: a rule with
   * exceptions is a rule someone has to remember, and the type that broke was also once obviously
   * closed.
   */
  public class Builder(width: Double, height: Double) {
    public var width: Double = width
    public var height: Double = height

    public fun build(): PageFrame = PageFrame(width, height)
  }

  /** This [PageFrame] as a [Builder], for deriving a modified one. Replaces `copy`. */
  public fun newBuilder(): Builder = Builder(width, height)
}

/** One imported page. */
@Serializable
@ConsistentCopyVisibility
public data class DesignPage
internal constructor(
  /** Stable slug, unique within the manifest; also the cached SVG's basename. */
  val id: String,
  /** The page's name in the design file. */
  val name: String,
  /** Node id of the page itself. */
  val nodeId: String,
  val frame: PageFrame,
  val image: PageImage,
  /** In the design file's own order, so a re-import diffs cleanly. */
  val nodes: List<PageNode> = emptyList(),
  /**
   * Whether this sheet is a **component inventory** — a page whose contents a catalog is measured
   * against.
   *
   * `false` is the kit's icon page: 499 `COMPONENT` nodes that are an icon set, not a component
   * inventory, and that no Compose catalog implements one-by-one. Counting them reported 499
   * missing components — a third of the whole kit's apparent gap — and drowned every real one.
   * `kit-sets.json` already excludes that page by name from the kit walk; this is the same
   * exclusion, stated where the page view can read it.
   *
   * The page is still imported, still drawn, and still browsable — this changes what the page
   * *claims*, not what it shows. [coverageTotal] is 0 for such a page and the view says what it is
   * instead of scoring it.
   *
   * Page-level rather than a `false` on all 499 nodes because it is a fact about the sheet, and
   * because 499 stamped nodes is a fact repeated 499 times that a re-import can get half-right.
   */
  val inventory: Boolean = true,
  /**
   * Shared raster plates painted **beneath** the design SVG, in paint order — first is furthest
   * back.
   *
   * The page's scene, not its content. Every lane draws these and draws them identically, so
   * switching between the design lane, the code lane and the diff changes which components sit on
   * the sheet and nothing about what they sit on. A component's own detail view deliberately does
   * NOT inherit them: a backplate is page context, and baking it into each component capture is the
   * flattening this whole mechanism exists to avoid.
   *
   * Empty for every page published before this field existed, and for every page that needs no
   * plate — which is most of them.
   */
  val background: List<PageLayerPlacement> = emptyList(),
  /**
   * How the sanitized design SVG composites over [background].
   *
   * `screen` for a kit whose component sets are authored that way. Stated per page rather than
   * inferred from the markup because the authored mode belongs to the *layer*, and the sanitizer
   * deliberately does not promote a `mix-blend-mode` it finds inside the export into a claim about
   * the whole sheet.
   */
  val designBlend: PageBlendMode = PageBlendMode.SOURCE_OVER,
  /**
   * How this catalog's injected renders composite over the same scene.
   *
   * Separate from [designBlend], and that separation is the point. Figma's Buttons page *authors*
   * screen blending; a Glimmer capture of the same components is additive radiance
   * ([PageBlendMode.PLUS_LIGHTER]). Compositing each the way it was actually produced is what makes
   * the two lanes comparable; forcing one mode on both would turn the diff into a measurement of
   * the mistake.
   */
  val renderBlend: PageBlendMode = PageBlendMode.SOURCE_OVER,
) {
  /**
   * Builds a [DesignPage].
   *
   * The way to construct one: [DesignPage]'s own constructor is `internal`, and with
   * `@ConsistentCopyVisibility` so is its generated `copy`. Neither is public ABI any more, so
   * adding a property here cannot remove a signature a precompiled consumer already calls -- which
   * is exactly what `NoSuchMethodError: DesignPage.copy$default(...)` was.
   *
   * The rule that keeps that true: **a new property is always optional**, so it only ever adds a
   * setter here and never a parameter to this constructor. A property that genuinely cannot have a
   * default is a new type, not a new parameter.
   */
  public class Builder(
    id: String,
    name: String,
    nodeId: String,
    frame: PageFrame,
    image: PageImage,
  ) {
    public var id: String = id
    public var name: String = name
    public var nodeId: String = nodeId
    public var frame: PageFrame = frame
    public var image: PageImage = image
    public var nodes: List<PageNode> = emptyList()
    public var inventory: Boolean = true
    public var background: List<PageLayerPlacement> = emptyList()
    public var designBlend: PageBlendMode = PageBlendMode.SOURCE_OVER
    public var renderBlend: PageBlendMode = PageBlendMode.SOURCE_OVER

    public fun build(): DesignPage =
      DesignPage(
        id,
        name,
        nodeId,
        frame,
        image,
        nodes,
        inventory,
        background,
        designBlend,
        renderBlend,
      )
  }

  /** This [DesignPage] as a [Builder], for deriving a modified one. Replaces `copy`. */
  public fun newBuilder(): Builder =
    Builder(id, name, nodeId, frame, image).also {
      it.nodes = nodes
      it.inventory = inventory
      it.background = background
      it.designBlend = designBlend
      it.renderBlend = renderBlend
    }

  /**
   * Nodes with code behind them — the numerator of the page's coverage.
   *
   * Empty for a page that is not an [inventory], together with [coverageGaps] and [coverageTotal]:
   * these three are the one fraction, and a consumer that read a numerator against a zero
   * denominator would print `12 of 0`. Use [nodes] for an honest node query.
   */
  public val linked: List<PageNode>
    get() = if (!inventory) emptyList() else nodes.filter { it.isComponent && !it.isUnlinked }

  /** Nodes with no code behind them. Not the same as [coverageGaps] — see there. */
  public val unlinked: List<PageNode>
    get() = nodes.filter { it.isUnlinked }

  /**
   * The nodes a reader means by *what we haven't implemented yet*: unlinked, and actually a
   * component someone could implement.
   *
   * Three kinds of unlinked node are not gaps, and on a real specimen sheet they are most of them:
   *
   * 1. **Private components** ([PageNode.isPrivate]) — the sheet's own furniture, never published.
   * 2. **Containers** ([PageNode.container]) — a `COMPONENT_SET`'s variants are the components; the
   *    set is the box they came in. Its variants are listed here in their own right, so counting
   *    the set as well reports one missing component for a family that is fully implemented.
   * 3. **Placements** ([PageNode.isPlacement]) — an `INSTANCE` is a use of a component, not a
   *    definition of one, and the definition is listed here in its own right too. See there; the
   *    kit's page headers are the case that named it.
   *
   * All three are read off the node, never inferred. An earlier cut worked container-ness out from
   * the walk's depth ordering — a node immediately followed by a deeper one — so a manifest
   * published before the producer stated it would still read correctly. That inference is unsound
   * in the direction that matters: a manifest lists components only, so an unlisted frame between
   * two of them lets a shallower node be followed by a deeper one that is not inside it, and a
   * genuinely missing component would then be swallowed as "structure". A stale manifest
   * over-counting a container is visible and harmless; a gap that quietly disappears is neither.
   */
  public val coverageGaps: List<PageNode>
    get() = if (!inventory) emptyList() else nodes.filter { it.isComponent && it.isUnlinked }

  /**
   * How many components on this page a catalog could implement — the denominator behind "N of M
   * implemented", and deliberately not `nodes.size`.
   *
   * Zero for a page that is not an [inventory], which is how a consumer tells "this sheet is fully
   * implemented" from "this sheet is not the kind of thing you implement": the first has a
   * numerator, and the second has no fraction to state at all.
   */
  public val coverageTotal: Int
    get() = if (!inventory) 0 else linked.size + coverageGaps.size
}

/** A committed design-page import. */
@Serializable
@ConsistentCopyVisibility
public data class DesignPagesManifest
internal constructor(
  val version: Int,
  /** Design source. Only Figma exposes a page-level read API today. */
  val source: String = "figma",
  /** The design-tool file the pages came from. */
  val fileKey: String,
  val pages: List<DesignPage> = emptyList(),
  /**
   * Bundle-local shared raster assets, keyed by content hash and referenced by
   * [DesignPage.background] placements.
   *
   * A table on the manifest rather than bytes on the page, so imagery repeated across sections and
   * pages is carried once. Empty for every manifest published before this field existed.
   */
  val assets: List<PageAsset> = emptyList(),
) {
  /**
   * Builds a [DesignPagesManifest].
   *
   * The way to construct one: [DesignPagesManifest]'s own constructor is `internal`, and with
   * `@ConsistentCopyVisibility` so is its generated `copy`. Neither is public ABI any more, so
   * adding a property here cannot remove a signature a precompiled consumer already calls -- which
   * is exactly what `NoSuchMethodError: DesignPage.copy$default(...)` was.
   *
   * The rule that keeps that true: **a new property is always optional**, so it only ever adds a
   * setter here and never a parameter to this constructor. A property that genuinely cannot have a
   * default is a new type, not a new parameter.
   */
  public class Builder(version: Int, fileKey: String) {
    public var version: Int = version
    public var fileKey: String = fileKey
    public var source: String = "figma"
    public var pages: List<DesignPage> = emptyList()
    public var assets: List<PageAsset> = emptyList()

    public fun build(): DesignPagesManifest =
      DesignPagesManifest(version, source, fileKey, pages, assets)
  }

  /** This [DesignPagesManifest] as a [Builder], for deriving a modified one. Replaces `copy`. */
  public fun newBuilder(): Builder =
    Builder(version, fileKey).also {
      it.source = source
      it.pages = pages
      it.assets = assets
    }

  /** Whether this build understands the manifest's version. */
  public val isSupported: Boolean
    get() = supportsDesignPagesVersion(version)

  /**
   * The well-formed assets, by id — the only ones a placement may resolve to.
   *
   * Filtered here rather than at each call site so an asset that fails [PageAsset.isWellFormed] (a
   * bad hash, an unknown format, a declared size past the caps) is invisible to every consumer at
   * once, and a placement naming it resolves to nothing rather than to something unchecked. A
   * duplicate id keeps the first record: ids are content hashes, so two records under one id
   * disagree about bytes that cannot differ, and picking the later one would let a malformed
   * trailing entry mask a good one.
   */
  public val assetsById: Map<String, PageAsset>
    get() {
      val byId = LinkedHashMap<String, PageAsset>()
      for (asset in assets) if (asset.isWellFormed) byId.putIfAbsent(asset.id, asset)
      return byId
    }

  /**
   * [page]'s background placements that actually resolve — well-formed boxes naming a well-formed
   * asset, in paint order.
   *
   * The one accessor a renderer should use. Dropping an unresolvable placement rather than failing
   * the page is the same fail-soft posture the rest of this surface takes: a sheet missing one
   * plate is worth drawing, and a sheet that refuses to draw because a plate went missing is not.
   */
  public fun backgroundFor(page: DesignPage): List<PageLayerPlacement> {
    val byId = assetsById
    return page.background.filter { it.isWellFormed && byId.containsKey(it.asset) }
  }

  /**
   * Asset ids some page places. The reachable set.
   *
   * Reads the raw [DesignPage.background] rather than [backgroundFor], deliberately: an id named by
   * a placement that is *currently* unresolvable is still referenced, and treating it as garbage
   * would have a validator delete the file and then report the reference as dangling on the next
   * run. Reachability is about what the manifest points at, not about what draws today.
   */
  public val referencedAssetIds: Set<String>
    get() = pages.flatMapTo(LinkedHashSet()) { page -> page.background.map { it.asset } }

  /**
   * Assets no page places — what a garbage collector removes and what `--check` reports.
   *
   * An unreachable asset is not a correctness problem, it is weight: a delivery branch is
   * append-only, so a plate that stops being placed would otherwise be carried forever by every
   * later publish.
   */
  public val unreachableAssets: List<PageAsset>
    get() {
      val referenced = referencedAssetIds
      return assets.filterNot { it.id in referenced }
    }

  /**
   * Placements that name an asset this manifest does not carry — dangling references.
   *
   * The other half of `--check`, and the one that is a real defect: a page asking for bytes the
   * bundle does not have draws a hole where the design put a backdrop.
   */
  public val danglingAssetRefs: List<String>
    get() {
      val known = assets.mapTo(HashSet(), PageAsset::id)
      return pages.flatMap { page -> page.background.map { it.asset } }.filterNot { it in known }
    }

  /**
   * The design ref for [node], deriving it when the producer didn't write one.
   *
   * Use this rather than [PageNode.ref] directly. A ref is `"figma:<fileKey>/<nodeId>"` and both
   * halves are already here, so reconstructing it is exact rather than a guess — and an unlinked
   * node's deep link into the design tool is the only link it has.
   */
  public fun refFor(node: PageNode): String = node.ref ?: "figma:$fileKey/${node.nodeId}"
}
