package dev.tilbrook.kandroid.typedmanifest

import kotlin.reflect.KClass

/**
 * Typed AndroidManifest.xml entries.
 *
 * The `dev.tilbrook.typed-manifest` Gradle plugin reads these annotations straight from source
 * (before compilation) and generates a manifest overlay that AGP merges into the variant.
 *
 * Rules that follow from source-level processing:
 * - **Only attributes you write are emitted.** The Kotlin defaults below are placeholders and
 *   are never written to the manifest. The one exception is [Activity.exported], which is
 *   always written and defaults to `false` (secure by default).
 * - Argument values must be literals, `Manifest.*` enum entries, `Manifest.Action` /
 *   `Manifest.Category` constants, `SomeClass::class` (Kotlin) / `SomeClass.class` (Java),
 *   arrays of those, or nested `Manifest.*` annotations. Arbitrary `const val` references are
 *   not resolved.
 */
object Manifest {

    /**
     * Declares an `<activity>` entry for the annotated class.
     *
     * ```kotlin
     * @Manifest.Activity(
     *     exported = true,
     *     theme = "@style/Theme.App",
     *     intentFilters = [
     *         Manifest.IntentFilter(
     *             actions = [Manifest.Action.MAIN],
     *             categories = [Manifest.Category.LAUNCHER],
     *         ),
     *     ],
     * )
     * class MainActivity : Activity()
     * ```
     *
     * Each parameter maps to the `android:` attribute of the same name. See
     * https://developer.android.com/guide/topics/manifest/activity-element
     */
    @MustBeDocumented
    @Target(AnnotationTarget.CLASS)
    @Retention(AnnotationRetention.SOURCE)
    annotation class Activity(
        /** Always emitted. `false` unless set; launcher activities must set `true`. */
        val exported: Boolean = false,
        val enabled: Boolean = true,
        val label: String = "",
        val description: String = "",
        val icon: String = "",
        val roundIcon: String = "",
        val banner: String = "",
        val logo: String = "",
        val theme: String = "",
        val permission: String = "",
        val process: String = "",
        val taskAffinity: String = "",
        /** Class reference written as `android:parentActivityName`. Set this or [parentActivityName], not both. */
        val parentActivity: KClass<*> = Nothing::class,
        val parentActivityName: String = "",
        val launchMode: LaunchMode = LaunchMode.STANDARD,
        val screenOrientation: ScreenOrientation = ScreenOrientation.UNSPECIFIED,
        val configChanges: Array<ConfigChange> = [],
        val windowSoftInputMode: Array<WindowSoftInputMode> = [],
        val documentLaunchMode: DocumentLaunchMode = DocumentLaunchMode.NONE,
        val persistableMode: PersistableMode = PersistableMode.PERSIST_ROOT_ONLY,
        val lockTaskMode: LockTaskMode = LockTaskMode.NORMAL,
        val uiOptions: UiOptions = UiOptions.NONE,
        val colorMode: ColorMode = ColorMode.DEFAULT,
        val requireContentUriPermissionFromCaller: ContentUriPermission = ContentUriPermission.NONE,
        val maxRecents: Int = 16,
        val maxAspectRatio: Float = 0f,
        val minAspectRatio: Float = 0f,
        val allowEmbedded: Boolean = false,
        val allowTaskReparenting: Boolean = false,
        val allowUntrustedActivityEmbedding: Boolean = false,
        val alwaysRetainTaskState: Boolean = false,
        val autoRemoveFromRecents: Boolean = false,
        val canDisplayOnRemoteDevices: Boolean = true,
        val clearTaskOnLaunch: Boolean = false,
        val directBootAware: Boolean = false,
        val enableOnBackInvokedCallback: Boolean = false,
        val excludeFromRecents: Boolean = false,
        val finishOnTaskLaunch: Boolean = false,
        val hardwareAccelerated: Boolean = true,
        val immersive: Boolean = false,
        val multiprocess: Boolean = false,
        val noHistory: Boolean = false,
        val preferMinimalPostProcessing: Boolean = false,
        val relinquishTaskIdentity: Boolean = false,
        val resizeableActivity: Boolean = true,
        val showForAllUsers: Boolean = false,
        val showWhenLocked: Boolean = false,
        val stateNotNeeded: Boolean = false,
        val supportsPictureInPicture: Boolean = false,
        val turnScreenOn: Boolean = false,
        val visibleToInstantApps: Boolean = false,
        val attributionTags: String = "",
        val knownActivityEmbeddingCerts: String = "",
        val requiredDisplayCategory: String = "",
        val splitName: String = "",
        val intentFilters: Array<IntentFilter> = [],
        val metaData: Array<MetaData> = [],
        val layout: Layout = Layout(),
    )

    /** `<intent-filter>` */
    @Target()
    @Retention(AnnotationRetention.SOURCE)
    annotation class IntentFilter(
        val actions: Array<String> = [],
        val categories: Array<String> = [],
        val data: Array<Data> = [],
        val autoVerify: Boolean = false,
        val priority: Int = 0,
        val order: Int = 0,
        val label: String = "",
        val icon: String = "",
        val roundIcon: String = "",
        val banner: String = "",
        val logo: String = "",
    )

    /** `<data>` inside an `<intent-filter>`. */
    @Target()
    @Retention(AnnotationRetention.SOURCE)
    annotation class Data(
        val scheme: String = "",
        val host: String = "",
        val port: String = "",
        val path: String = "",
        val pathPrefix: String = "",
        val pathPattern: String = "",
        val pathSuffix: String = "",
        val pathAdvancedPattern: String = "",
        val mimeType: String = "",
    )

    /** `<meta-data>`. Set either [value] or [resource]. */
    @Target()
    @Retention(AnnotationRetention.SOURCE)
    annotation class MetaData(
        val name: String,
        val value: String = "",
        val resource: String = "",
    )

    /** `<layout>` */
    @Target()
    @Retention(AnnotationRetention.SOURCE)
    annotation class Layout(
        val defaultWidth: String = "",
        val defaultHeight: String = "",
        val gravity: String = "",
        val minWidth: String = "",
        val minHeight: String = "",
    )

    /** Common intent actions. */
    object Action {
        const val MAIN = "android.intent.action.MAIN"
        const val VIEW = "android.intent.action.VIEW"
        const val SEND = "android.intent.action.SEND"
        const val SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
        const val SENDTO = "android.intent.action.SENDTO"
        const val EDIT = "android.intent.action.EDIT"
        const val PICK = "android.intent.action.PICK"
        const val SEARCH = "android.intent.action.SEARCH"
        const val APPLICATION_PREFERENCES = "android.intent.action.APPLICATION_PREFERENCES"
    }

    /** Common intent categories. */
    object Category {
        const val DEFAULT = "android.intent.category.DEFAULT"
        const val LAUNCHER = "android.intent.category.LAUNCHER"
        const val BROWSABLE = "android.intent.category.BROWSABLE"
        const val INFO = "android.intent.category.INFO"
        const val HOME = "android.intent.category.HOME"
        const val LEANBACK_LAUNCHER = "android.intent.category.LEANBACK_LAUNCHER"
        const val PREFERENCE = "android.intent.category.PREFERENCE"
    }

    enum class LaunchMode(val manifestValue: String) {
        STANDARD("standard"),
        SINGLE_TOP("singleTop"),
        SINGLE_TASK("singleTask"),
        SINGLE_INSTANCE("singleInstance"),
        SINGLE_INSTANCE_PER_TASK("singleInstancePerTask"),
    }

    enum class ScreenOrientation(val manifestValue: String) {
        UNSPECIFIED("unspecified"),
        BEHIND("behind"),
        LANDSCAPE("landscape"),
        PORTRAIT("portrait"),
        REVERSE_LANDSCAPE("reverseLandscape"),
        REVERSE_PORTRAIT("reversePortrait"),
        SENSOR_LANDSCAPE("sensorLandscape"),
        SENSOR_PORTRAIT("sensorPortrait"),
        USER_LANDSCAPE("userLandscape"),
        USER_PORTRAIT("userPortrait"),
        SENSOR("sensor"),
        FULL_SENSOR("fullSensor"),
        NO_SENSOR("nosensor"),
        USER("user"),
        FULL_USER("fullUser"),
        LOCKED("locked"),
    }

    enum class ConfigChange(val manifestValue: String) {
        MCC("mcc"),
        MNC("mnc"),
        LOCALE("locale"),
        TOUCHSCREEN("touchscreen"),
        KEYBOARD("keyboard"),
        KEYBOARD_HIDDEN("keyboardHidden"),
        NAVIGATION("navigation"),
        SCREEN_LAYOUT("screenLayout"),
        FONT_SCALE("fontScale"),
        UI_MODE("uiMode"),
        ORIENTATION("orientation"),
        DENSITY("density"),
        SCREEN_SIZE("screenSize"),
        SMALLEST_SCREEN_SIZE("smallestScreenSize"),
        LAYOUT_DIRECTION("layoutDirection"),
        COLOR_MODE("colorMode"),
        GRAMMATICAL_GENDER("grammaticalGender"),
        FONT_WEIGHT_ADJUSTMENT("fontWeightAdjustment"),
    }

    enum class WindowSoftInputMode(val manifestValue: String) {
        STATE_UNSPECIFIED("stateUnspecified"),
        STATE_UNCHANGED("stateUnchanged"),
        STATE_HIDDEN("stateHidden"),
        STATE_ALWAYS_HIDDEN("stateAlwaysHidden"),
        STATE_VISIBLE("stateVisible"),
        STATE_ALWAYS_VISIBLE("stateAlwaysVisible"),
        ADJUST_UNSPECIFIED("adjustUnspecified"),
        ADJUST_RESIZE("adjustResize"),
        ADJUST_PAN("adjustPan"),
        ADJUST_NOTHING("adjustNothing"),
    }

    enum class DocumentLaunchMode(val manifestValue: String) {
        INTO_EXISTING("intoExisting"),
        ALWAYS("always"),
        NONE("none"),
        NEVER("never"),
    }

    enum class PersistableMode(val manifestValue: String) {
        PERSIST_ROOT_ONLY("persistRootOnly"),
        PERSIST_ACROSS_REBOOTS("persistAcrossReboots"),
        PERSIST_NEVER("persistNever"),
    }

    enum class LockTaskMode(val manifestValue: String) {
        NORMAL("normal"),
        NEVER("never"),
        IF_WHITELISTED("if_whitelisted"),
        ALWAYS("always"),
    }

    enum class UiOptions(val manifestValue: String) {
        NONE("none"),
        SPLIT_ACTION_BAR_WHEN_NARROW("splitActionBarWhenNarrow"),
    }

    enum class ColorMode(val manifestValue: String) {
        DEFAULT("default"),
        WIDE_COLOR_GAMUT("wideColorGamut"),
        HDR("hdr"),
    }

    enum class ContentUriPermission(val manifestValue: String) {
        NONE("none"),
        READ("read"),
        WRITE("write"),
        READ_OR_WRITE("readOrWrite"),
        READ_AND_WRITE("readAndWrite"),
    }
}
