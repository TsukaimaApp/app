package app.tsukaima.domain.chat

import app.tsukaima.data.api.ProviderDto

/** One pickable model string as the server names it (`deepseek-v4.1-flash:speed`), labelled by its flavor. */
data class ModelFlavor(val label: String, val model: String)

/**
 * A model as the picker lists it: [base] is the name before any `:flavor` suffix; [flavors] holds every variant
 * the provider offers under that base (the bare name first, as "default"), or nothing when there is only the
 * bare name, in which case picking the entry picks [base] itself.
 */
data class ModelEntry(val base: String, val flavors: List<ModelFlavor>) {
    /** Every server model string under this entry. */
    val models: List<String> get() = if (flavors.isEmpty()) listOf(base) else flavors.map { it.model }
}

data class ProviderEntry(val slug: String, val name: String, val models: List<ModelEntry>)

/**
 * Groups `/api/model/options` into provider → model → flavor for the picker. A provider's `deepseek-v4.1-flash`
 * and `deepseek-v4.1-flash:speed` become one entry with two flavors; `gpt-6.1-sol-900k` stays a plain entry.
 * Providers the user has not authenticated are left out; order is the server's.
 */
object ModelTree {
    const val DEFAULT_FLAVOR = "default"

    fun build(providers: List<ProviderDto>): List<ProviderEntry> =
        providers.filter { it.authenticated != false }.map { p ->
            ProviderEntry(p.slug, p.name?.takeIf { it.isNotBlank() } ?: p.slug, entries(p.models))
        }

    private fun entries(models: List<String>): List<ModelEntry> {
        val byBase = LinkedHashMap<String, MutableList<String>>()
        for (m in models.distinct()) byBase.getOrPut(base(m)) { mutableListOf() } += m
        return byBase.map { (base, variants) ->
            if (variants.size == 1 && variants.single() == base) {
                ModelEntry(base, emptyList())
            } else {
                // The bare name first, then the flavors in the server's order.
                val ordered = variants.sortedBy { if (it == base) 0 else 1 }
                ModelEntry(base, ordered.map { ModelFlavor(if (it == base) DEFAULT_FLAVOR else flavor(it), it) })
            }
        }
    }

    /** `deepseek-v4.1-flash:speed` → `deepseek-v4.1-flash`; a name without a flavor is its own base. */
    fun base(model: String): String = model.substringBefore(':')

    /** `deepseek-v4.1-flash:speed` → `speed`; empty for a bare name. */
    fun flavor(model: String): String = model.substringAfter(':', "")
}
