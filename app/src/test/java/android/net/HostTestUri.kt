package android.net

import android.os.Parcel
import java.lang.reflect.Field
import sun.misc.Unsafe

/** Host-only Uri whose Android framework constructor is bypassed for local adapter tests. */
class HostTestUri private constructor() : Uri() {
    private var rawValue: String? = null

    override fun toString(): String = checkNotNull(rawValue)
    override fun getScheme(): String? = rawValue?.substringBefore(':')
    override fun isHierarchical(): Boolean = true
    override fun isRelative(): Boolean = false
    override fun getSchemeSpecificPart(): String? = rawValue?.substringAfter(':')
    override fun getEncodedSchemeSpecificPart(): String? = schemeSpecificPart
    override fun getAuthority(): String? = rawValue?.substringAfter("//", "")?.substringBefore('/')
    override fun getEncodedAuthority(): String? = authority
    override fun getUserInfo(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getHost(): String? = authority
    override fun getPort(): Int = -1
    override fun getPath(): String? = rawValue?.substringAfter("//", "")?.substringAfter('/', "")?.let { "/$it" }
    override fun getEncodedPath(): String? = path
    override fun getQuery(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getPathSegments(): List<String> = path?.split('/')?.filter(String::isNotEmpty).orEmpty()
    override fun getLastPathSegment(): String? = pathSegments.lastOrNull()
    override fun buildUpon(): Builder = throw UnsupportedOperationException("Host adapter fixture")
    override fun describeContents(): Int = 0
    override fun writeToParcel(parcel: Parcel, flags: Int) = Unit

    companion object {
        fun create(value: String): HostTestUri =
            unsafe.allocateInstance(HostTestUri::class.java).let { instance ->
                (instance as HostTestUri).apply { rawValue = value }
            }

        private val unsafe: Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").let { field: Field ->
            field.isAccessible = true
            field.get(null) as Unsafe
        }
    }
}
