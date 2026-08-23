package au.com.elied.vitalsignal.wear.transport

/**
 * Conservative application budget for the canonical encrypted bytes carried inside a DataItem.
 *
 * Android documents DataItems as generally small and recommends Assets for larger objects. The
 * budget leaves room for DataMap framing and metadata; it is an engineering limit rather than a
 * claim about an undocumented platform maximum. Oversized research captures must use a separately
 * authenticated, receipt-tested Asset or chunk protocol rather than silently entering this path.
 */
object WearDataItemPayloadPolicy {
    const val MAX_CANONICAL_WIRE_BYTES: Int = 64 * 1024
    const val OVERSIZE_CODE: String = "data_item_payload_budget_exceeded"

    fun rejectionCode(canonicalWireSizeBytes: Int): String? {
        require(canonicalWireSizeBytes >= 0) { "Canonical wire size must be non-negative" }
        return OVERSIZE_CODE.takeIf {
            canonicalWireSizeBytes > MAX_CANONICAL_WIRE_BYTES
        }
    }
}
