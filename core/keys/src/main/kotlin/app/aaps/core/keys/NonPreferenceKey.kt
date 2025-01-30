package app.aaps.core.keys

/**
 * Defines shared preference encapsulation that works inside a module without preferences UI
 */
interface NonPreferenceKey {

    /**
     * Associated [android.content.SharedPreferences] key
     */
    val key: String
}